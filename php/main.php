<?php

/**
 * Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
 * в структуру: страна / город / улица / дом / квартира + нормализованное написание
 * и коэффициент качества разбора.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *   php main.php "мск ленинскй проспект д4 кв5"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальным
 * разбором) — чтобы можно было встроить и протестировать интеграцию до оплаты.
 * Ответы детерминированы: одна и та же строка всегда даёт один и тот же результат.
 *
 * ВАЖНО ПРО ПЕСОЧНИЦУ: мок НЕ разбирает вашу строку. Он генерирует случайный
 * правдоподобный адрес и всегда ставит quality = 100. Порог качества ниже на моках
 * не срабатывает — он заработает только на боевом ключе. Подробно — в README.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 30;

/** Пауза перед повтором после 429 и число повторов. */
const RETRY_DELAY = 20;
const MAX_RETRIES = 1;

/**
 * Потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит подождать десятки
 * минут — и клиент, слепо доверяющий заголовку Retry-After, «зависнет» на всё это
 * время (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
 */
const MAX_RETRY_DELAY = 120;

/**
 * ПОРОГ КАЧЕСТВА — главная настройка всего сценария.
 *
 * quality — коэффициент качества разбора, 0..100. 100 — идеальный разбор без лишних
 * фрагментов; >= 90 — нормально; ниже 80 — к результату стоит относиться осторожно.
 *
 * Смысл порога: молча записать криво разобранный адрес в базу ХУЖЕ, чем отправить
 * его человеку. Уверенные случаи автоматизируем, сомнительные эскалируем.
 */
const QUALITY_THRESHOLD = 80;

const ACCEPTED = 'ПРИНЯТ';
const MANUAL = 'НА ПРОВЕРКУ';

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        429 => 'Превышен лимит запросов — повторите позже',
        501 => 'Комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован',
        503 => 'Сервис временно недоступен (плановые работы) — повторите позже',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf(
            'HTTP %d: %s. Ответ сервера: %s',
            $status,
            $reason,
            mb_substr($body, 0, 200)
        ));
    }
}

final class AddressStdClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * Разбор адресной строки: GET /api/addressstd?address=...
     *
     * $region — код региона по умолчанию (например, 77), если в строке региона нет.
     * Разбор выполняется локально, без обращений к внешним источникам, — отсюда время
     * ответа в единицы миллисекунд (поле `milliseconds` в ответе).
     *
     * @return array<string, mixed>
     */
    public function standardize(string $address, ?int $region = null): array
    {
        $params = ['address' => $address];
        if ($region !== null) {
            $params['region'] = (string) $region;
        }
        $url = $this->baseUrl . '/api/addressstd?' . http_build_query($params);

        for ($attempt = 0; $attempt <= MAX_RETRIES; $attempt++) {
            [$status, $body, $retryAfter] = $this->request($url);

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if ($status === 429 && $attempt < MAX_RETRIES) {
                $delay = self::retryDelay($retryAfter);
                if ($delay === 0) {
                    throw new AtloriumError(429, 'лимит ключа по IP исчерпан, повторите позже');
                }
                fwrite(STDERR, "  ... лимит запросов, пауза {$delay} с\n");
                sleep($delay);
                continue;
            }

            if ($status !== 200) {
                throw new AtloriumError($status, $body);
            }

            return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        }

        throw new AtloriumError(429, 'лимит запросов не отпустил после повтора');
    }

    /** @return array{0: int, 1: string, 2: int} — статус, тело, значение Retry-After */
    private function request(string $url): array
    {
        $curl = curl_init($url);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HEADER => true,
            CURLOPT_TIMEOUT => TIMEOUT,
            CURLOPT_HTTPHEADER => [
                'Authorization: Bearer ' . $this->apiKey,
                'Accept: application/json',
            ],
        ]);

        $raw = curl_exec($curl);
        if ($raw === false) {
            $error = curl_error($curl);
            curl_close($curl);
            throw new RuntimeException("Сетевая ошибка: {$error}");
        }

        $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        $headerSize = curl_getinfo($curl, CURLINFO_HEADER_SIZE);
        curl_close($curl);

        $headers = substr((string) $raw, 0, $headerSize);
        $body = substr((string) $raw, $headerSize);

        $retryAfter = 0;
        if (preg_match('/^Retry-After:\s*(\d+)/mi', $headers, $matches) === 1) {
            $retryAfter = (int) $matches[1];
        }

        return [$status, $body, $retryAfter];
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означало бы busy-loop, а «40 минут» (так сервер отвечает на исчерпанное часовое
     * окно) — зависание клиента. Вернём 0, если ждать бессмысленно долго.
     */
    private static function retryDelay(int $retryAfter): int
    {
        if ($retryAfter <= 0) {
            return RETRY_DELAY;
        }
        return $retryAfter <= MAX_RETRY_DELAY ? $retryAfter : 0;
    }
}

// ── Применение данных: чистка адресов в CRM ───────────────────────────────────
// Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
// принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
// должен посмотреть человек.
//
// Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
// всё сомнительное эскалируем, а не портим базу молча.

/**
 * Первый компонент нужного уровня.
 *
 * Уровни (сверху вниз): Country, RegionArea, RegionCity, District, Settlement, City,
 * CityDistrict, Locality, Territory, Street, Plot, Building, Apartment, Room.
 *
 * @param array<string, mixed> $parsed
 * @return array<string, mixed>|null
 */
function componentOf(array $parsed, string $level): ?array
{
    foreach ($parsed['components'] ?? [] as $component) {
        if (($component['level'] ?? '') === $level) {
            return $component;
        }
    }

    return null;
}

/**
 * Собственное имя элемента: «Тверская» вместо «улица Тверская».
 * `names` — разобранные имена, `text` — готовое нормализованное написание.
 *
 * @param array<string, mixed>|null $component
 */
function nameOf(?array $component): string
{
    if ($component === null) {
        return '';
    }
    $names = $component['names'] ?? [];

    return $names !== [] && $names !== null ? implode(' ', $names) : (string) ($component['text'] ?? '');
}

/**
 * Номер дома вместе с корпусом и строением: «4 к.1 стр.2».
 *
 * @param array<string, mixed>|null $component
 */
function houseOf(?array $component): string
{
    if ($component === null) {
        return '';
    }

    // Сравниваем с '' явно, а не через empty(): empty('0') === true, и дом номер «0»
    // молча потерялся бы.
    $parts = [];
    if ((string) ($component['number'] ?? '') !== '') {
        $parts[] = (string) $component['number'];
    }
    if ((string) ($component['buildNumber'] ?? '') !== '') {
        $parts[] = 'к.' . $component['buildNumber'];
    }
    if ((string) ($component['structNumber'] ?? '') !== '') {
        $parts[] = 'стр.' . $component['structNumber'];
    }

    return implode(' ', $parts);
}

/**
 * Чистка адресной базы: каждый адрес разбирается и получает решение.
 * Список намеренно короткий: сервис тарифицируется за запрос.
 *
 * @param list<string> $addresses
 * @return list<array<string, mixed>>
 */
function cleanAddressDatabase(AddressStdClient $client, array $addresses): array
{
    $rows = [];

    foreach ($addresses as $source) {
        $parsed = $client->standardize($source);

        $quality = (int) ($parsed['quality'] ?? 0);
        $street = nameOf(componentOf($parsed, 'Street'));
        $house = houseOf(componentOf($parsed, 'Building'));
        $apartment = (string) (componentOf($parsed, 'Apartment')['number'] ?? '');

        // ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
        if ($quality < QUALITY_THRESHOLD) {
            $decision = MANUAL;
            $reason = "качество {$quality} < порога " . QUALITY_THRESHOLD;
        } elseif ($street === '' || $house === '') {
            // Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
            // доставить по нему нельзя. Тоже человеку.
            $decision = MANUAL;
            $reason = 'не выделены улица или дом';
        } else {
            $decision = ACCEPTED;
            $reason = "качество {$quality} >= порога " . QUALITY_THRESHOLD;
        }

        // `message` — сообщение о неточности, влияющей на качество. Подсказка оператору.
        if (!empty($parsed['message'])) {
            $reason .= '; ' . $parsed['message'];
        }

        $rows[] = [
            'source' => $source,
            'normalized' => (string) ($parsed['normalized'] ?? ''),
            'quality' => $quality,
            'country' => (string) ($parsed['countryCode'] ?? '—'),
            'city' => nameOf(componentOf($parsed, 'City')),
            'street' => $street,
            'house' => $house,
            'apartment' => $apartment,
            'decision' => $decision,
            'reason' => $reason,
            'elapsedMs' => (int) ($parsed['milliseconds'] ?? 0),
        ];
    }

    return $rows;
}

// ── Печать ────────────────────────────────────────────────────────────────────
// Ширина колонок считается в СИМВОЛАХ (mb_*), а не в байтах: в кириллице один
// символ — два байта, и обычный str_pad разъехался бы.

function padRight(string $text, int $width): string
{
    $length = mb_strlen($text);

    return $length >= $width ? $text : $text . str_repeat(' ', $width - $length);
}

function padLeft(string $text, int $width): string
{
    $length = mb_strlen($text);

    return $length >= $width ? $text : str_repeat(' ', $width - $length) . $text;
}

function cut(string $text, int $width): string
{
    return mb_strlen($text) <= $width ? $text : mb_substr($text, 0, $width - 1) . '…';
}

function dash(string $text): string
{
    return $text === '' ? '—' : $text;
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

$client = new AddressStdClient();

if ($client->isSandbox()) {
    echo "Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n\n";
}

// Первый адрес — из аргумента командной строки, остальные два «грязные»:
// так люди и вводят адреса в реальные формы.
$primary = $argv[1] ?? 'г Москва, ул. Тверская, д. 7, кв. 12';
$addresses = [
    $primary,
    'мск ленинскй проспект д4 кв5', // сокращение города + опечатка в улице
    'спб невский пр-кт 28',         // сокращения, нет запятых, нет квартиры
];

echo 'Чистка адресной базы. Адресов на входе: ' . count($addresses) . "\n\n";

try {
    $rows = cleanAddressDatabase($client, $addresses);
} catch (AtloriumError $error) {
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(1);
}

foreach ($rows as $index => $row) {
    $number = $index + 1;
    echo "[{$number}] «{$row['source']}»\n";
    echo "    Нормализовано: {$row['normalized']}\n";
    echo "    Качество: {$row['quality']} · Страна: {$row['country']} · {$row['elapsedMs']} мс\n";
    echo '    Город: ' . dash($row['city']) . ' | Улица: ' . dash($row['street'])
        . ' | Дом: ' . dash($row['house']) . ' | Кв.: ' . dash($row['apartment']) . "\n";
    echo "    {$row['decision']}: {$row['reason']}\n\n";
}

echo padRight('ИСХОДНАЯ СТРОКА', 34) . padRight('НОРМАЛИЗОВАННЫЙ АДРЕС', 46)
    . padLeft('КАЧ', 4) . "  РЕШЕНИЕ\n";

$accepted = 0;
foreach ($rows as $row) {
    if ($row['decision'] === ACCEPTED) {
        $accepted++;
    }
    echo padRight(cut($row['source'], 33), 34)
        . padRight(cut($row['normalized'], 45), 46)
        . padLeft((string) $row['quality'], 4)
        . "  {$row['decision']}\n";
}

$total = count($rows);
echo "\nИТОГО\n";
echo "  Обработано адресов:    {$total}\n";
echo "  Принято автоматически: {$accepted}\n";
echo '  На ручную проверку:    ' . ($total - $accepted) . "\n";
echo "\nПорог качества: " . QUALITY_THRESHOLD . ". Молча записать криво разобранный адрес в базу\n";
echo "хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.\n";

if ($client->isSandbox()) {
    echo "\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —\n";
    echo "    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.\n";
    echo "    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.\n";
}
