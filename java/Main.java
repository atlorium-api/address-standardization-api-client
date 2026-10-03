/*
 * Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
 * в структуру: страна / город / улица / дом / квартира + нормализованное написание
 * и коэффициент качества разбора.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *     java Main.java "мск ленинскй проспект д4 кв5"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальным
     * разбором) — чтобы можно было встроить и протестировать интеграцию до оплаты.
     * Ответы детерминированы: одна и та же строка всегда даёт один и тот же результат.
     *
     * ВАЖНО ПРО ПЕСОЧНИЦУ: мок НЕ разбирает вашу строку. Он генерирует случайный
     * правдоподобный адрес и всегда ставит quality = 100. Порог качества ниже на моках
     * не срабатывает — он заработает только на боевом ключе. Подробно — в README.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /** Пауза перед повтором после 429 и число повторов. */
    static final int RETRY_DELAY_S = 20;
    static final int MAX_RETRIES = 1;

    /**
     * Потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит подождать десятки
     * минут — и клиент, слепо доверяющий заголовку Retry-After, «зависнет» на всё это
     * время (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
     */
    static final int MAX_RETRY_DELAY_S = 120;

    /**
     * ПОРОГ КАЧЕСТВА — главная настройка всего сценария.
     *
     * quality — коэффициент качества разбора, 0..100. 100 — идеальный разбор без лишних
     * фрагментов; >= 90 — нормально; ниже 80 — к результату стоит относиться осторожно.
     *
     * Смысл порога: молча записать криво разобранный адрес в базу ХУЖЕ, чем отправить
     * его человеку. Уверенные случаи автоматизируем, сомнительные эскалируем.
     */
    static final int QUALITY_THRESHOLD = 80;

    static final String ACCEPTED = "ПРИНЯТ";
    static final String MANUAL = "НА ПРОВЕРКУ";

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = Map.of(
                400, "Строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)",
                401, "API-ключ отсутствует, просрочен или недействителен",
                402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
                429, "Превышен лимит запросов — повторите позже",
                501, "Комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован",
                503, "Сервис временно недоступен (плановые работы) — повторите позже");

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означало бы busy-loop, а «40 минут» (так сервер отвечает на исчерпанное часовое
     * окно) — зависание клиента. Вернём 0, если ждать бессмысленно долго.
     */
    static int retryAfter(HttpResponse<String> response) {
        int seconds = response.headers().firstValue("Retry-After")
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw.trim());
                    } catch (NumberFormatException error) {
                        return 0;
                    }
                })
                .orElse(0);

        if (seconds <= 0) {
            return RETRY_DELAY_S;
        }
        return seconds <= MAX_RETRY_DELAY_S ? seconds : 0;
    }

    /**
     * Разбор адресной строки: GET /api/addressstd?address=...
     *
     * region — код региона по умолчанию (например, 77), если в строке региона нет;
     * ноль означает «не передавать». Разбор выполняется локально, без обращений к
     * внешним источникам, — отсюда время ответа в единицы миллисекунд.
     */
    static String standardize(String address, int region) throws IOException, InterruptedException {
        String query = "address=" + URLEncoder.encode(address, StandardCharsets.UTF_8);
        if (region > 0) {
            query += "&region=" + region;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/addressstd?" + query))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                int delay = retryAfter(response);
                if (delay == 0) {
                    throw new AtloriumException(429, "лимит ключа по IP исчерпан, повторите позже");
                }
                System.err.println("  ... лимит запросов, пауза " + delay + " с");
                Thread.sleep(delay * 1000L);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new AtloriumException(response.statusCode(), response.body());
            }
            return response.body();
        }

        throw new AtloriumException(429, "лимит запросов не отпустил после повтора");
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson и
    // маппьте ответ в полноценную запись — эти регулярки существуют только ради
    // отсутствия pom.xml.

    static String str(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : null;
    }

    static int number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** Массив строк: "names":["16","Парковая"] -> ["16", "Парковая"]. */
    static List<String> array(String json, String field) {
        List<String> values = new ArrayList<>();
        Matcher block = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!block.find()) {
            return values;
        }
        Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        return values;
    }

    /**
     * Объекты массива components[]. Элементы плоские (внутри только строки, числа и
     * массивы строк), поэтому вложенных фигурных скобок в них нет — этого достаточно,
     * чтобы разрезать массив регуляркой без полноценного парсера.
     *
     * А вот границу самого массива регуляркой не найти: внутри элементов есть
     * вложенные массивы ("types", "names"), и первая же ']' — не конец components.
     * Поэтому ищем закрывающую скобку счётчиком глубины.
     */
    static List<String> components(String json) {
        List<String> objects = new ArrayList<>();
        int start = json.indexOf("\"components\"");
        if (start < 0) {
            return objects;
        }
        int open = json.indexOf('[', start);
        if (open < 0) {
            return objects;
        }

        int depth = 0;
        int close = -1;
        for (int i = open; i < json.length(); i++) {
            char symbol = json.charAt(i);
            if (symbol == '[') {
                depth++;
            } else if (symbol == ']' && --depth == 0) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return objects;
        }

        Matcher matcher = Pattern.compile("\\{[^{}]*\\}").matcher(json.substring(open, close));
        while (matcher.find()) {
            objects.add(matcher.group());
        }
        return objects;
    }

    /** Первый компонент нужного уровня: City, Street, Building, Apartment, … */
    static String componentOf(String json, String level) {
        for (String component : components(json)) {
            if (level.equals(str(component, "level"))) {
                return component;
            }
        }
        return null;
    }

    /**
     * Собственное имя элемента: «Тверская» вместо «улица Тверская».
     * names — разобранные имена, text — готовое нормализованное написание.
     */
    static String nameOf(String component) {
        if (component == null) {
            return "";
        }
        List<String> names = array(component, "names");
        if (!names.isEmpty()) {
            return String.join(" ", names);
        }
        String text = str(component, "text");
        return text == null ? "" : text;
    }

    /** Номер дома вместе с корпусом и строением: «4 к.1 стр.2». */
    static String houseOf(String component) {
        if (component == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String number = str(component, "number");
        if (number != null) {
            parts.add(number);
        }
        String build = str(component, "buildNumber");
        if (build != null) {
            parts.add("к." + build);
        }
        String struct = str(component, "structNumber");
        if (struct != null) {
            parts.add("стр." + struct);
        }
        return String.join(" ", parts);
    }

    // ── Применение данных: чистка адресов в CRM ──────────────────────────────
    // Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
    // принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
    // должен посмотреть человек.
    //
    // Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
    // всё сомнительное эскалируем, а не портим базу молча.

    /** Строка адресной базы после чистки — то, что уходит в колонки CRM. */
    record CleanedAddress(
            String source,
            String normalized,
            int quality,
            String country,
            String city,
            String street,
            String house,
            String apartment,
            String decision,
            String reason,
            int elapsedMs) {
    }

    static CleanedAddress cleanOne(String source) throws IOException, InterruptedException {
        String parsed = standardize(source, 0);

        int quality = number(parsed, "quality");
        String street = nameOf(componentOf(parsed, "Street"));
        String house = houseOf(componentOf(parsed, "Building"));

        String apartmentComponent = componentOf(parsed, "Apartment");
        String apartment = apartmentComponent == null ? "" : String.valueOf(str(apartmentComponent, "number"));
        if ("null".equals(apartment)) {
            apartment = "";
        }

        // ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
        String decision;
        String reason;

        if (quality < QUALITY_THRESHOLD) {
            decision = MANUAL;
            reason = "качество " + quality + " < порога " + QUALITY_THRESHOLD;
        } else if (street.isEmpty() || house.isEmpty()) {
            // Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
            // доставить по нему нельзя. Тоже человеку.
            decision = MANUAL;
            reason = "не выделены улица или дом";
        } else {
            decision = ACCEPTED;
            reason = "качество " + quality + " >= порога " + QUALITY_THRESHOLD;
        }

        // message — сообщение о неточности, влияющей на качество. Готовая подсказка оператору.
        String message = str(parsed, "message");
        if (message != null && !message.isBlank()) {
            reason = reason + "; " + message;
        }

        String country = str(parsed, "countryCode");

        return new CleanedAddress(
                source,
                String.valueOf(str(parsed, "normalized")),
                quality,
                country == null ? "—" : country,
                nameOf(componentOf(parsed, "City")),
                street,
                house,
                apartment,
                decision,
                reason,
                number(parsed, "milliseconds"));
    }

    /**
     * Чистка адресной базы: каждый адрес разбирается и получает решение.
     * Список намеренно короткий: сервис тарифицируется за запрос.
     */
    static List<CleanedAddress> cleanAddressDatabase(List<String> addresses)
            throws IOException, InterruptedException {
        List<CleanedAddress> rows = new ArrayList<>();
        for (String address : addresses) {
            rows.add(cleanOne(address));
        }
        return rows;
    }

    // ── Печать ───────────────────────────────────────────────────────────────

    static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    static String padLeft(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    static String cut(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }

    static String orDash(String text) {
        return (text == null || text.isEmpty()) ? "—" : text;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
        }

        // Первый адрес — из аргумента командной строки, остальные два «грязные»:
        // так люди и вводят адреса в реальные формы.
        String primary = args.length > 0 ? args[0] : "г Москва, ул. Тверская, д. 7, кв. 12";
        List<String> addresses = List.of(
                primary,
                "мск ленинскй проспект д4 кв5", // сокращение города + опечатка в улице
                "спб невский пр-кт 28");        // сокращения, нет запятых, нет квартиры

        System.out.println("Чистка адресной базы. Адресов на входе: " + addresses.size() + "\n");

        List<CleanedAddress> rows;
        try {
            rows = cleanAddressDatabase(addresses);
        } catch (AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
            return;
        }

        for (int index = 0; index < rows.size(); index++) {
            CleanedAddress row = rows.get(index);
            System.out.println("[" + (index + 1) + "] «" + row.source() + "»");
            System.out.println("    Нормализовано: " + row.normalized());
            System.out.println("    Качество: " + row.quality() + " · Страна: " + row.country()
                    + " · " + row.elapsedMs() + " мс");
            System.out.println("    Город: " + orDash(row.city()) + " | Улица: " + orDash(row.street())
                    + " | Дом: " + orDash(row.house()) + " | Кв.: " + orDash(row.apartment()));
            System.out.println("    " + row.decision() + ": " + row.reason() + "\n");
        }

        System.out.println(pad("ИСХОДНАЯ СТРОКА", 34) + pad("НОРМАЛИЗОВАННЫЙ АДРЕС", 46)
                + padLeft("КАЧ", 4) + "  РЕШЕНИЕ");

        int accepted = 0;
        for (CleanedAddress row : rows) {
            if (ACCEPTED.equals(row.decision())) {
                accepted++;
            }
            System.out.println(pad(cut(row.source(), 33), 34)
                    + pad(cut(row.normalized(), 45), 46)
                    + padLeft(String.valueOf(row.quality()), 4)
                    + "  " + row.decision());
        }

        System.out.println("\nИТОГО");
        System.out.println("  Обработано адресов:    " + rows.size());
        System.out.println("  Принято автоматически: " + accepted);
        System.out.println("  На ручную проверку:    " + (rows.size() - accepted));
        System.out.println("\nПорог качества: " + QUALITY_THRESHOLD
                + ". Молча записать криво разобранный адрес в базу");
        System.out.println("хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.");

        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —");
            System.out.println("    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.");
            System.out.println("    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.");
        }
    }
}
