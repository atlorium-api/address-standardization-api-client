// Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
// в структуру: страна / город / улица / дом / квартира + нормализованное написание
// и коэффициент качества разбора.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//	go run . "мск ленинскй проспект д4 кв5"
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// SandboxKey — публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ
// (не реальным разбором), чтобы можно было встроить интеграцию до оплаты.
// Ответы детерминированы — на них можно писать стабильные тесты.
//
// ВАЖНО ПРО ПЕСОЧНИЦУ: мок НЕ разбирает вашу строку. Он генерирует случайный
// правдоподобный адрес и всегда ставит quality = 100. Порог качества ниже на моках
// не срабатывает — он заработает только на боевом ключе. Подробно — в README.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// Пауза перед повтором после 429 и число повторов.
const (
	retryDelay = 20 * time.Second
	maxRetries = 1

	// MaxRetryDelay — потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит
	// подождать десятки минут — и клиент, слепо доверяющий заголовку Retry-After,
	// «зависнет» на всё это время (а в CI съест бюджет джоба). Дольше потолка не ждём.
	MaxRetryDelay = 120 * time.Second
)

// QualityThreshold — ПОРОГ КАЧЕСТВА, главная настройка всего сценария.
//
// quality — коэффициент качества разбора, 0..100. 100 — идеальный разбор без лишних
// фрагментов; >= 90 — нормально; ниже 80 — к результату стоит относиться осторожно.
//
// Смысл порога: молча записать криво разобранный адрес в базу ХУЖЕ, чем отправить
// его человеку. Уверенные случаи автоматизируем, сомнительные эскалируем.
const QualityThreshold = 80

// Решения по адресу.
const (
	Accepted = "ПРИНЯТ"
	Manual   = "НА ПРОВЕРКУ"
)

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: 30 * time.Second}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// AddressComponent — один разобранный элемент адреса.
//
// Level — адресный уровень: Country, RegionArea, RegionCity, District, Settlement,
// City, CityDistrict, Locality, Territory, Street, Plot, Building, Apartment, Room.
type AddressComponent struct {
	Level        string   `json:"level"`
	LevelTitle   string   `json:"levelTitle"`   // «Улица», «Дом/здание», …
	Text         string   `json:"text"`         // нормализованное представление: «улица 16 Парковая»
	Types        []string `json:"types"`        // ["улица"], ["город"]
	Names        []string `json:"names"`        // ["16", "Парковая"]
	Number       string   `json:"number"`       // номер дома, участка, квартиры
	BuildNumber  string   `json:"buildNumber"`  // корпус — только для домов
	StructNumber string   `json:"structNumber"` // строение — только для домов
	PlotNumber   string   `json:"plotNumber"`
	Misc         []string `json:"misc"`
}

// StandardizedAddress — итог стандартизации одной адресной строки.
type StandardizedAddress struct {
	Input        string             `json:"input"`      // эхо запроса
	Normalized   string             `json:"normalized"` // нормализованный адрес одной строкой
	Quality      int                `json:"quality"`    // коэффициент качества разбора, 0..100
	CountryCode  string             `json:"countryCode"`
	Message      string             `json:"message"` // неточность, ВЛИЯЮЩАЯ на качество
	Info         string             `json:"info"`    // сообщение, НЕ влияющее на качество
	Components   []AddressComponent `json:"components"`
	Extra        map[string]string  `json:"extra"`        // этаж, а/я и прочее, не вписавшееся в модель
	Milliseconds int                `json:"milliseconds"` // время разбора
	// Gar и Geo — задел: сейчас всегда null (комбо-режимы в разработке, см. README).
	Gar json.RawMessage `json:"gar"`
	Geo json.RawMessage `json:"geo"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов на балансе — пополните на https://atlorium.com",
		429: "превышен лимит запросов — повторите позже",
		501: "комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован",
		503: "сервис временно недоступен (плановые работы) — повторите позже",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

// retryAfter — сколько ждать после 429. Ноль/мусор и слишком большие значения не
// берём на веру: 0 означало бы busy-loop, а «40 минут» (так сервер отвечает на
// исчерпанное часовое окно) — зависание клиента. Вернём 0, если ждать бессмысленно долго.
func retryAfter(response *http.Response) time.Duration {
	seconds, err := strconv.Atoi(response.Header.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		return retryDelay
	}
	delay := time.Duration(seconds) * time.Second
	if delay > MaxRetryDelay {
		return 0
	}
	return delay
}

// Standardize разбирает адресную строку: GET /api/addressstd?address=...
//
// region — код региона по умолчанию (например, 77), если в строке региона нет;
// ноль означает «не передавать». Разбор выполняется локально, без обращений к
// внешним источникам, — отсюда время ответа в единицы миллисекунд.
func Standardize(address string, region int) (*StandardizedAddress, error) {
	query := url.Values{"address": {address}}
	if region > 0 {
		query.Set("region", strconv.Itoa(region))
	}
	endpoint := baseURL + "/api/addressstd?" + query.Encode()

	for attempt := 0; attempt <= maxRetries; attempt++ {
		request, err := http.NewRequest(http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+apiKey)
		request.Header.Set("Accept", "application/json")

		response, err := client.Do(request)
		if err != nil {
			return nil, err
		}

		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			return nil, err
		}

		// 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
		if response.StatusCode == http.StatusTooManyRequests && attempt < maxRetries {
			delay := retryAfter(response)
			if delay == 0 {
				return nil, &APIError{Status: 429, Body: "лимит ключа по IP исчерпан, повторите позже"}
			}
			fmt.Fprintf(os.Stderr, "  ... лимит запросов, пауза %s\n", delay)
			time.Sleep(delay)
			continue
		}

		if response.StatusCode != http.StatusOK {
			return nil, &APIError{Status: response.StatusCode, Body: string(body)}
		}

		var parsed StandardizedAddress
		if err := json.Unmarshal(body, &parsed); err != nil {
			return nil, err
		}
		return &parsed, nil
	}

	return nil, &APIError{Status: 429, Body: "лимит запросов не отпустил после повтора"}
}

// ── Применение данных: чистка адресов в CRM ───────────────────────────────────
// Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
// принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
// должен посмотреть человек.
//
// Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
// всё сомнительное эскалируем, а не портим базу молча.

// CleanedAddress — строка адресной базы после чистки: то, что уходит в колонки CRM.
type CleanedAddress struct {
	Source     string
	Normalized string
	Quality    int
	Country    string
	City       string
	Street     string
	House      string
	Apartment  string
	Decision   string
	Reason     string
	ElapsedMs  int
}

func componentOf(parsed *StandardizedAddress, level string) *AddressComponent {
	for i := range parsed.Components {
		if parsed.Components[i].Level == level {
			return &parsed.Components[i]
		}
	}
	return nil
}

// nameOf возвращает собственное имя элемента: «Тверская» вместо «улица Тверская».
// Names — разобранные имена, Text — готовое нормализованное написание.
func nameOf(component *AddressComponent) string {
	if component == nil {
		return ""
	}
	if len(component.Names) > 0 {
		return strings.Join(component.Names, " ")
	}
	return component.Text
}

// houseOf возвращает номер дома вместе с корпусом и строением: «4 к.1 стр.2».
func houseOf(component *AddressComponent) string {
	if component == nil {
		return ""
	}
	parts := make([]string, 0, 3)
	if component.Number != "" {
		parts = append(parts, component.Number)
	}
	if component.BuildNumber != "" {
		parts = append(parts, "к."+component.BuildNumber)
	}
	if component.StructNumber != "" {
		parts = append(parts, "стр."+component.StructNumber)
	}
	return strings.Join(parts, " ")
}

func cleanOne(source string) (CleanedAddress, error) {
	parsed, err := Standardize(source, 0)
	if err != nil {
		return CleanedAddress{}, err
	}

	street := nameOf(componentOf(parsed, "Street"))
	house := houseOf(componentOf(parsed, "Building"))

	apartment := ""
	if component := componentOf(parsed, "Apartment"); component != nil {
		apartment = component.Number
	}

	// ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
	var decision, reason string
	switch {
	case parsed.Quality < QualityThreshold:
		decision = Manual
		reason = fmt.Sprintf("качество %d < порога %d", parsed.Quality, QualityThreshold)
	case street == "" || house == "":
		// Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
		// доставить по нему нельзя. Тоже человеку.
		decision = Manual
		reason = "не выделены улица или дом"
	default:
		decision = Accepted
		reason = fmt.Sprintf("качество %d >= порога %d", parsed.Quality, QualityThreshold)
	}

	// Message — сообщение о неточности, влияющей на качество. Готовая подсказка оператору.
	if parsed.Message != "" {
		reason += "; " + parsed.Message
	}

	country := parsed.CountryCode
	if country == "" {
		country = "—"
	}

	return CleanedAddress{
		Source:     source,
		Normalized: parsed.Normalized,
		Quality:    parsed.Quality,
		Country:    country,
		City:       nameOf(componentOf(parsed, "City")),
		Street:     street,
		House:      house,
		Apartment:  apartment,
		Decision:   decision,
		Reason:     reason,
		ElapsedMs:  parsed.Milliseconds,
	}, nil
}

// CleanAddressDatabase разбирает каждый адрес и выносит по нему решение.
// Список намеренно короткий: сервис тарифицируется за запрос.
func CleanAddressDatabase(addresses []string) ([]CleanedAddress, error) {
	rows := make([]CleanedAddress, 0, len(addresses))
	for _, address := range addresses {
		row, err := cleanOne(address)
		if err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
	return rows, nil
}

// ── Печать ────────────────────────────────────────────────────────────────────
// Ширина колонок считается в РУНАХ, а не в байтах: в кириллице один символ — два
// байта, и обычный %-34s разъехался бы.

func pad(text string, width int) string {
	if runes := len([]rune(text)); runes < width {
		return text + strings.Repeat(" ", width-runes)
	}
	return text
}

func padLeft(text string, width int) string {
	if runes := len([]rune(text)); runes < width {
		return strings.Repeat(" ", width-runes) + text
	}
	return text
}

func cut(text string, width int) string {
	runes := []rune(text)
	if len(runes) <= width {
		return text
	}
	return string(runes[:width-1]) + "…"
}

func orDash(text string) string {
	if text == "" {
		return "—"
	}
	return text
}

func main() {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.")
		fmt.Println()
	}

	// Первый адрес — из аргумента командной строки, остальные два «грязные»:
	// так люди и вводят адреса в реальные формы.
	primary := "г Москва, ул. Тверская, д. 7, кв. 12"
	if len(os.Args) > 1 {
		primary = os.Args[1]
	}
	addresses := []string{
		primary,
		"мск ленинскй проспект д4 кв5", // сокращение города + опечатка в улице
		"спб невский пр-кт 28",         // сокращения, нет запятых, нет квартиры
	}

	fmt.Printf("Чистка адресной базы. Адресов на входе: %d\n\n", len(addresses))

	rows, err := CleanAddressDatabase(addresses)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}

	for index, row := range rows {
		fmt.Printf("[%d] «%s»\n", index+1, row.Source)
		fmt.Printf("    Нормализовано: %s\n", row.Normalized)
		fmt.Printf("    Качество: %d · Страна: %s · %d мс\n", row.Quality, row.Country, row.ElapsedMs)
		fmt.Printf("    Город: %s | Улица: %s | Дом: %s | Кв.: %s\n",
			orDash(row.City), orDash(row.Street), orDash(row.House), orDash(row.Apartment))
		fmt.Printf("    %s: %s\n\n", row.Decision, row.Reason)
	}

	fmt.Printf("%s%s%s  РЕШЕНИЕ\n",
		pad("ИСХОДНАЯ СТРОКА", 34), pad("НОРМАЛИЗОВАННЫЙ АДРЕС", 46), padLeft("КАЧ", 4))

	accepted := 0
	for _, row := range rows {
		if row.Decision == Accepted {
			accepted++
		}
		fmt.Printf("%s%s%s  %s\n",
			pad(cut(row.Source, 33), 34),
			pad(cut(row.Normalized, 45), 46),
			padLeft(strconv.Itoa(row.Quality), 4),
			row.Decision)
	}

	fmt.Println("\nИТОГО")
	fmt.Printf("  Обработано адресов:    %d\n", len(rows))
	fmt.Printf("  Принято автоматически: %d\n", accepted)
	fmt.Printf("  На ручную проверку:    %d\n", len(rows)-accepted)
	fmt.Printf("\nПорог качества: %d. Молча записать криво разобранный адрес в базу\n", QualityThreshold)
	fmt.Println("хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.")

	if apiKey == SandboxKey {
		fmt.Println("\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —")
		fmt.Println("    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.")
		fmt.Println("    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.")
	}
}
