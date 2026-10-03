// Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
// в структуру: страна / город / улица / дом / квартира + нормализованное написание
// и коэффициент качества разбора.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//     dotnet run -- "мск ленинскй проспект д4 кв5"
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;

// Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальным
// разбором) — чтобы можно было встроить и протестировать интеграцию до оплаты.
// Ответы детерминированы: одна и та же строка всегда даёт один и тот же результат.
//
// ВАЖНО ПРО ПЕСОЧНИЦУ: мок НЕ разбирает вашу строку. Он генерирует случайный
// правдоподобный адрес и всегда ставит quality = 100. Порог качества ниже на моках
// не срабатывает — он заработает только на боевом ключе. Подробно — в README.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(30),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new AddressStdClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
}

// Первый адрес — из аргумента командной строки, остальные два «грязные»:
// так люди и вводят адреса в реальные формы.
var primary = args.Length > 0 ? args[0] : "г Москва, ул. Тверская, д. 7, кв. 12";
string[] addresses =
[
    primary,
    "мск ленинскй проспект д4 кв5", // сокращение города + опечатка в улице
    "спб невский пр-кт 28",         // сокращения, нет запятых, нет квартиры
];

Console.WriteLine($"Чистка адресной базы. Адресов на входе: {addresses.Length}\n");

IReadOnlyList<CleanedAddress> rows;
try
{
    rows = await AddressDatabaseCleaner.CleanAsync(client, addresses);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

for (var index = 0; index < rows.Count; index++)
{
    var row = rows[index];
    Console.WriteLine($"[{index + 1}] «{row.Source}»");
    Console.WriteLine($"    Нормализовано: {row.Normalized}");
    Console.WriteLine($"    Качество: {row.Quality} · Страна: {row.Country} · {row.ElapsedMs} мс");
    Console.WriteLine($"    Город: {Dash(row.City)} | Улица: {Dash(row.Street)} | " +
                      $"Дом: {Dash(row.House)} | Кв.: {Dash(row.Apartment)}");
    Console.WriteLine($"    {row.Decision}: {row.Reason}\n");
}

Console.WriteLine($"{"ИСХОДНАЯ СТРОКА",-34}{"НОРМАЛИЗОВАННЫЙ АДРЕС",-46}{"КАЧ",4}  РЕШЕНИЕ");
foreach (var row in rows)
{
    Console.WriteLine($"{Cut(row.Source, 33),-34}{Cut(row.Normalized, 45),-46}{row.Quality,4}  {row.Decision}");
}

var accepted = rows.Count(row => row.Decision == AddressDatabaseCleaner.Accepted);

Console.WriteLine("\nИТОГО");
Console.WriteLine($"  Обработано адресов:    {rows.Count}");
Console.WriteLine($"  Принято автоматически: {accepted}");
Console.WriteLine($"  На ручную проверку:    {rows.Count - accepted}");
Console.WriteLine($"\nПорог качества: {AddressDatabaseCleaner.QualityThreshold}. " +
                  "Молча записать криво разобранный адрес в базу");
Console.WriteLine("хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.");

if (apiKey == SandboxKey)
{
    Console.WriteLine("\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —");
    Console.WriteLine("    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.");
    Console.WriteLine("    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.");
}

return 0;

static string Dash(string value) => string.IsNullOrEmpty(value) ? "—" : value;

static string Cut(string text, int width) => text.Length <= width ? text : text[..(width - 1)] + "…";

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429 => "Превышен лимит запросов — повторите позже",
        501 => "Комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован",
        503 => "Сервис временно недоступен (плановые работы) — повторите позже",
        _ => "Неизвестная ошибка",
    };
}

public sealed class AddressStdClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    /// <summary>Пауза перед повтором после 429 и число повторов.</summary>
    private const int RetryDelaySeconds = 20;

    private const int MaxRetries = 1;

    /// <summary>
    /// Потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит подождать десятки
    /// минут — и клиент, слепо доверяющий заголовку Retry-After, «зависнет» на всё это
    /// время (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
    /// </summary>
    private const int MaxRetryDelaySeconds = 120;

    /// <summary>
    /// Разбор адресной строки: <c>GET /api/addressstd?address=...</c>
    /// </summary>
    /// <param name="address">Адресная строка. Опечатки, сокращения и переставленные части допускаются.</param>
    /// <param name="region">
    /// Код региона по умолчанию (например, 77), если в строке региона нет: помогает
    /// разобрать «улица + дом» без верхнего уровня. Необязателен.
    /// </param>
    public async Task<StandardizedAddress> StandardizeAsync(string address, int? region = null)
    {
        var path = $"/api/addressstd?address={Uri.EscapeDataString(address)}";
        if (region is { } code)
        {
            path += $"&region={code}";
        }

        for (var attempt = 0; attempt <= MaxRetries; attempt++)
        {
            using var response = await http.GetAsync(path);

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < MaxRetries)
            {
                var delay = RetryAfter(response);
                if (delay == 0)
                {
                    throw new AtloriumException(HttpStatusCode.TooManyRequests,
                        "лимит ключа по IP исчерпан, повторите позже");
                }
                Console.Error.WriteLine($"  ... лимит запросов, пауза {delay} с");
                await Task.Delay(TimeSpan.FromSeconds(delay));
                continue;
            }

            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode)
            {
                throw new AtloriumException(response.StatusCode, body);
            }

            return JsonSerializer.Deserialize<StandardizedAddress>(body, JsonOptions)
                   ?? throw new InvalidOperationException("Пустой ответ API.");
        }

        throw new AtloriumException(HttpStatusCode.TooManyRequests, "лимит запросов не отпустил после повтора");
    }

    /// <summary>
    /// Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
    /// 0 означало бы busy-loop, а «40 минут» (так сервер отвечает на исчерпанное часовое
    /// окно) — зависание клиента. Вернём 0, если ждать бессмысленно долго.
    /// </summary>
    private static int RetryAfter(HttpResponseMessage response)
    {
        var seconds = (int?)response.Headers.RetryAfter?.Delta?.TotalSeconds ?? 0;
        if (seconds <= 0)
        {
            return RetryDelaySeconds;
        }
        return seconds <= MaxRetryDelaySeconds ? seconds : 0;
    }
}

// ── Модель ответа ────────────────────────────────────────────────────────────

/// <summary>Адресный уровень элемента — сверху вниз, от страны к комнате.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<AddressLevel>))]
public enum AddressLevel
{
    Undefined,
    Country,
    RegionArea,
    RegionCity,
    District,
    Settlement,
    City,
    CityDistrict,
    Locality,
    Territory,
    Street,
    Plot,
    Building,
    Apartment,
    Room,
}

/// <summary>Один разобранный элемент адреса.</summary>
public sealed record AddressComponent
{
    public AddressLevel Level { get; init; }

    /// <summary>Название уровня по-русски: «Улица», «Дом/здание», …</summary>
    public string LevelTitle { get; init; } = "";

    /// <summary>Нормализованное представление элемента одной строкой: «улица 16 Парковая».</summary>
    public string Text { get; init; } = "";

    /// <summary>Тип элемента: ["улица"], ["город"].</summary>
    public IReadOnlyList<string>? Types { get; init; }

    /// <summary>Собственные имена: ["16", "Парковая"].</summary>
    public IReadOnlyList<string>? Names { get; init; }

    /// <summary>Основной номер: дома, участка, квартиры, комнаты.</summary>
    public string? Number { get; init; }

    /// <summary>Корпус — только для домов.</summary>
    public string? BuildNumber { get; init; }

    /// <summary>Строение — только для домов.</summary>
    public string? StructNumber { get; init; }

    /// <summary>Номер земельного участка.</summary>
    public string? PlotNumber { get; init; }

    /// <summary>Второстепенные уточнения.</summary>
    public IReadOnlyList<string>? Misc { get; init; }
}

/// <summary>Итог стандартизации одной адресной строки.</summary>
public sealed record StandardizedAddress
{
    /// <summary>Эхо запроса: строка, как её прислали.</summary>
    public string Input { get; init; } = "";

    /// <summary>Нормализованный адрес одной строкой.</summary>
    public string Normalized { get; init; } = "";

    /// <summary>Коэффициент качества разбора, 0..100.</summary>
    public int Quality { get; init; }

    public string? CountryCode { get; init; }

    /// <summary>Сообщение о неточности, ВЛИЯЮЩЕЙ на качество.</summary>
    public string? Message { get; init; }

    /// <summary>Информационное сообщение, НЕ влияющее на качество.</summary>
    public string? Info { get; init; }

    public IReadOnlyList<AddressComponent> Components { get; init; } = [];

    /// <summary>Элементы, не вписавшиеся в модель: этаж, а/я и т.п.</summary>
    public IReadOnlyDictionary<string, string>? Extra { get; init; }

    /// <summary>Время разбора, мс.</summary>
    public int Milliseconds { get; init; }

    /// <summary>Привязка к ГАР/ФИАС. Задел: сейчас всегда null (комбо-режим в разработке).</summary>
    public JsonElement? Gar { get; init; }

    /// <summary>Координаты. Задел: сейчас всегда null (комбо-режим в разработке).</summary>
    public JsonElement? Geo { get; init; }
}

// ── Применение данных: чистка адресов в CRM ───────────────────────────────────
// Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
// принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
// должен посмотреть человек.
//
// Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
// всё сомнительное эскалируем, а не портим базу молча.

/// <summary>Строка адресной базы после чистки — то, что уходит в колонки CRM.</summary>
public sealed record CleanedAddress(
    string Source,
    string Normalized,
    int Quality,
    string Country,
    string City,
    string Street,
    string House,
    string Apartment,
    string Decision,
    string Reason,
    int ElapsedMs);

public static class AddressDatabaseCleaner
{
    public const string Accepted = "ПРИНЯТ";
    public const string Manual = "НА ПРОВЕРКУ";

    /// <summary>
    /// ПОРОГ КАЧЕСТВА — главная настройка всего сценария.
    ///
    /// quality — коэффициент качества разбора, 0..100. 100 — идеальный разбор без лишних
    /// фрагментов; ≥ 90 — нормально; ниже 80 — к результату стоит относиться осторожно.
    ///
    /// Смысл порога: молча записать криво разобранный адрес в базу ХУЖЕ, чем отправить
    /// его человеку. Уверенные случаи автоматизируем, сомнительные эскалируем.
    /// </summary>
    public const int QualityThreshold = 80;

    /// <summary>
    /// Чистка адресной базы: каждый адрес разбирается и получает решение.
    /// Список намеренно короткий: сервис тарифицируется за запрос.
    /// </summary>
    public static async Task<IReadOnlyList<CleanedAddress>> CleanAsync(
        AddressStdClient client, IEnumerable<string> addresses)
    {
        var rows = new List<CleanedAddress>();
        foreach (var address in addresses)
        {
            rows.Add(Decide(address, await client.StandardizeAsync(address)));
        }
        return rows;
    }

    private static CleanedAddress Decide(string source, StandardizedAddress parsed)
    {
        var street = NameOf(Component(parsed, AddressLevel.Street));
        var house = HouseOf(Component(parsed, AddressLevel.Building));
        var apartment = Component(parsed, AddressLevel.Apartment)?.Number ?? "";

        // ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
        string decision;
        string reason;

        if (parsed.Quality < QualityThreshold)
        {
            decision = Manual;
            reason = $"качество {parsed.Quality} < порога {QualityThreshold}";
        }
        else if (street.Length == 0 || house.Length == 0)
        {
            // Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
            // доставить по нему нельзя. Тоже человеку.
            decision = Manual;
            reason = "не выделены улица или дом";
        }
        else
        {
            decision = Accepted;
            reason = $"качество {parsed.Quality} >= порога {QualityThreshold}";
        }

        // Message — сообщение о неточности, влияющей на качество. Готовая подсказка оператору.
        if (!string.IsNullOrWhiteSpace(parsed.Message))
        {
            reason = $"{reason}; {parsed.Message}";
        }

        return new CleanedAddress(
            source,
            parsed.Normalized,
            parsed.Quality,
            string.IsNullOrEmpty(parsed.CountryCode) ? "—" : parsed.CountryCode,
            NameOf(Component(parsed, AddressLevel.City)),
            street,
            house,
            apartment,
            decision,
            reason,
            parsed.Milliseconds);
    }

    private static AddressComponent? Component(StandardizedAddress parsed, AddressLevel level)
        => parsed.Components.FirstOrDefault(component => component.Level == level);

    /// <summary>
    /// Собственное имя элемента: «Тверская» вместо «улица Тверская».
    /// Names — разобранные имена, Text — готовое нормализованное написание.
    /// </summary>
    private static string NameOf(AddressComponent? component)
    {
        if (component is null)
        {
            return "";
        }
        return component.Names is { Count: > 0 } names ? string.Join(' ', names) : component.Text;
    }

    /// <summary>Номер дома вместе с корпусом и строением: «4 к.1 стр.2».</summary>
    private static string HouseOf(AddressComponent? component)
    {
        if (component is null)
        {
            return "";
        }

        var parts = new List<string>(3);
        if (!string.IsNullOrEmpty(component.Number))
        {
            parts.Add(component.Number);
        }
        if (!string.IsNullOrEmpty(component.BuildNumber))
        {
            parts.Add($"к.{component.BuildNumber}");
        }
        if (!string.IsNullOrEmpty(component.StructNumber))
        {
            parts.Add($"стр.{component.StructNumber}");
        }
        return string.Join(' ', parts);
    }
}
