# Address Standardization API — parse and normalize a raw address string

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/address-standardization-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/address-standardization-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/addressstdAPI)

Ready-to-run examples for the **address standardization API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
**Normalize an address** typed by a human in any shape and **parse it into components**: country, region, city, street, building, block, apartment — plus a **parse quality score** telling you how confident the engine is.

Input is what people actually type. Output is structure plus a canonical one-line form. Built for **address cleansing** in CRMs and legacy databases.

Parsing runs **locally**, with no calls to third-party services — hence single-digit millisecond responses.

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in.

```bash
git clone https://github.com/atlorium-api/address-standardization-api-client
cd address-standardization-api-client/python && pip install -r requirements.txt && python main.py
```

Real output on the demo key — including every sandbox quirk, [explained below](#what-is-actually-wrong-with-the-sandbox-read-this-before-you-are-surprised):

```
Демо-ключ: ответы сгенерированы (моки), не реальные данные.

Чистка адресной базы. Адресов на входе: 3

[1] «г Москва, ул. Тверская, д. 7, кв. 12»
    Нормализовано: город Киров, улица Восточная проспект, д.4, кв.153
    Качество: 100 · Страна: RU · 5 мс
    Город: Киров | Улица: Восточная проспект | Дом: 4 | Кв.: 153
    ПРИНЯТ: качество 100 >= порога 80

[2] «мск ленинскй проспект д4 кв5»
    Нормализовано: город Воронеж, улица Энергетиков проспект, д.88, кв.202
    Качество: 100 · Страна: RU · 17 мс
    Город: Воронеж | Улица: Энергетиков проспект | Дом: 88 | Кв.: 202
    ПРИНЯТ: качество 100 >= порога 80

[3] «спб невский пр-кт 28»
    Нормализовано: город Ярославль, улица Солнечная пл., д.116, кв.164
    Качество: 100 · Страна: RU · 5 мс
    Город: Ярославль | Улица: Солнечная пл. | Дом: 116 | Кв.: 164
    ПРИНЯТ: качество 100 >= порога 80

ИСХОДНАЯ СТРОКА                   НОРМАЛИЗОВАННЫЙ АДРЕС                          КАЧ  РЕШЕНИЕ
г Москва, ул. Тверская, д. 7, кв… город Киров, улица Восточная проспект, д.4, …  100  ПРИНЯТ
мск ленинскй проспект д4 кв5      город Воронеж, улица Энергетиков проспект, д…  100  ПРИНЯТ
спб невский пр-кт 28              город Ярославль, улица Солнечная пл., д.116,…  100  ПРИНЯТ

ИТОГО
  Обработано адресов:    3
  Принято автоматически: 3
  На ручную проверку:    0

Порог качества: 80. Молча записать криво разобранный адрес в базу
хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.

[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —
    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.
    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.
```

The examples print in Russian — the service is aimed at Russian addresses. In short, the run above says: three dirty addresses in, all three parsed, all three auto-accepted, none sent to manual review.

> And yes, you read that right: given `Moscow, Tverskaya st.` the sandbox answered `city of Kirov`. We did **not** massage the output to look good — that is literally what the example prints on the demo key. Why, and what still works, is the [next section](#what-is-actually-wrong-with-the-sandbox-read-this-before-you-are-surprised). Swap in a live key and the same code parses the address for real.

---

## What it is for

People type addresses however they like: `NYC`, `New York`, `ул. Лесная`, `Лесная ул.`, `д.4к1`, `apt 5`. In a database that becomes a mess you cannot deduplicate, route deliveries by, or export.

Standardization turns that mess into structure: city, street, building and apartment as separate fields — plus one canonical string.

**Typical jobs:** address cleansing in CRMs and legacy databases, canonicalizing form input before saving, parsing addresses out of emails and imports, preparing data for logistics, deduplicating customers and delivery points, migrating off an old system.

### The core idea: a quality score and an automation threshold

The examples do not just print JSON — they **apply** it. Each ships a `cleanAddressDatabase()` function that does the thing people actually buy this service for.

For every dirty address it:

1. gets the parse — the normalized string and the `components[]` array;
2. pulls out the CRM columns: **city, street, building (with block and structure), apartment**;
3. **decides, using a quality threshold.**

```python
QUALITY_THRESHOLD = 80

if quality < QUALITY_THRESHOLD:
    decision = "MANUAL REVIEW"   # send it to a human
else:
    decision = "ACCEPTED"        # write it to the database automatically
```

**Why the threshold matters.** Silently writing a badly parsed address into your database is **worse** than handing it to a human: nobody notices the corrupted row, and the parcel goes to the wrong place. So confident parses are automated, doubtful ones are escalated. The threshold is the dial that trades "operator hours saved" against "garbage let into the database".

A second filter in the example: even at high quality, an address goes to a human if **no street or no building** was extracted — you cannot deliver to it anyway.

The result is a summary: how many addresses were accepted automatically, how many went to manual review.

## What is actually wrong with the sandbox (read this before you are surprised)

The demo key is a **plausible-data generator, not a real parser**. The mock **does not look at your string**: it seeds off it and generates a random, plausible Russian address.

Two consequences, both visible in the output above:

| What happens | Why |
|---|---|
| The normalized address is **unrelated** to the input: `Moscow, Tverskaya 7` → `city of Sochi, Zarechnaya sq., 69, apt 227` | The mock generates an address instead of parsing yours |
| `quality` is **always 100** — even for deliberately broken input | The mock does not score anything, it just stamps the maximum |
| City and street inside one line **do not agree** with each other | Components are generated independently |

**The practical upshot: the quality threshold never fires in the sandbox.** On mocks you will never see a `MANUAL REVIEW` row — everything is auto-accepted, because quality is always 100. The filtering only starts working with a live key. We say this plainly, because you would find out anyway and the lie would cost more than the truth.

What **does** work and is genuinely useful:

- **The response shape is real.** Every field, every `components[]` level, every type and nesting is exactly what a live key returns. You can write the whole integration against it.
- **Responses are deterministic.** The same string always yields the same result, so you can assert on it in tests.
- **The `MANUAL REVIEW` branch is still testable** without a live key: raise `QUALITY_THRESHOLD` above 100 and the whole list is escalated. The branching logic gets exercised; the parse quality does not.

What you **cannot** do in the sandbox: judge parse quality or component completeness. That needs a live key.

## Quick start

Try the API without cloning anything:

```bash
curl -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     "https://atlorium.com/api/addressstd?address=г Москва, ул. Тверская, д. 7, кв. 12" --get
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 17+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own address as an argument:

```bash
python main.py "мск ленинскй проспект д4 кв5"
```

It becomes the first entry in the demo list; two more dirty addresses are baked in so the summary has more than one row. **Three API calls per run** in total.

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. Returns mocks, charges nothing, needs no account. Responses are deterministic, so you can assert on them in tests. |
| Live key | Real address parsing. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Every sandbox response carries the header `X-Atlorium-Sandbox: true`, so mock data can never be mistaken for real data.

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/addressstd` | Parse an address string into structure + normalize + score the parse |

### `GET /api/addressstd`

| Parameter | In | Type | Description |
|-----------|----|------|-------------|
| `address` | query | string | **The address line.** Typos, abbreviations, lower case and reordered parts are all tolerated |
| `region` | query | int | Default region code (e.g. `77`) when the string has no region. Helps parse a bare "street + building". Optional |
| `bind` | query | bool | **Not implemented yet** — bind the parsed address to the GAR/FIAS registry. Currently returns `501` and is **not billed** |
| `geocode` | query | bool | **Not implemented yet** — attach coordinates. Currently returns `501` and is **not billed** |

To be straight about `bind` and `geocode`: they are in the contract as a placeholder and will be switched on later. Until then they return 501 — and you are not charged. If you need GAR/FIAS binding **today**, that is a separate service: [gar-fias-address-api-client](https://github.com/atlorium-api/gar-fias-address-api-client).

## Response fields

Taken from **live API responses** and cross-checked against the [OpenAPI spec](https://atlorium.com/openapi/addressstd_en-US.json).

| Field | Type | Meaning |
|-------|------|---------|
| `input` | string | Echo of the request |
| `normalized` | string | **The canonical one-line address** assembled from the parsed components |
| `quality` | int | **Parse quality score, 0..100.** The key field. `100` — perfect parse, nothing left over; `>= 90` — fine; **below 80 — treat with caution** |
| `countryCode` | string | Country code (`RU`, …) if detected. May be `null` |
| `message` | string | A note about an inaccuracy that **affected** the score (e.g. an unparsable fragment). Ready-made hint for an operator. May be `null` |
| `info` | string | Informational note that **did not affect** the score. May be `null` |
| `components` | array | **Parsed address elements**, top-down from country to apartment (see below) |
| `extra` | object | Elements that do not fit the model: floor, PO box, normalized company name. May be `null` |
| `milliseconds` | int | Parse time |
| `gar` | object | GAR/FIAS binding. **Placeholder: always `null` today** (see `bind`) |
| `geo` | object | Coordinates. **Placeholder: always `null` today** (see `geocode`) |

### The `components[]` element

| Field | Type | Meaning |
|-------|------|---------|
| `level` | string | **Address level** (see below) |
| `levelTitle` | string | Human-readable level name |
| `text` | string | Normalized one-line form of the element |
| `types` | array | Element type: `["улица"]` (street), `["город"]` (city). May be `null` |
| `names` | array | **The proper name** of the element: `["16", "Парковая"]`. This is what goes into a CRM column. May be `null` |
| `number` | string | Primary number: building, plot, apartment, room. May be `null` |
| `buildNumber` | string | **Block** — buildings only. May be `null` |
| `structNumber` | string | **Structure** — buildings only. May be `null` |
| `plotNumber` | string | Land plot number. May be `null` |
| `misc` | array | Secondary qualifiers. May be `null` |

`text` versus `names`: `text` is display-ready (`улица Тверская` — "Tverskaya street"), `names` is the parsed name only (`Тверская`). CRM columns usually want `names`; a printed envelope wants `text`.

### `level` values

Top-down, from country to room:

`Undefined` · `Country` · `RegionArea` · `RegionCity` · `District` · `Settlement` · `City` · `CityDistrict` · `Locality` · `Territory` · `Street` · `Plot` · `Building` · `Apartment` · `Room`

Only the levels **actually extracted** from the string are present. No apartment in the address means no `Apartment` component. That is why the examples look components up by level instead of indexing into the array.

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | No string given, or **no address could be extracted** from it | Check the `address` parameter. **Not billed** — no work was done |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Insufficient credit balance | Top up at [atlorium.com](https://atlorium.com) |
| `429` | Rate limit exceeded | Retry with backoff — **but cap the wait**, see below |
| `501` | `bind` or `geocode` requested | Not implemented yet. **Not billed** |
| `503` | Service temporarily unavailable: scheduled maintenance | Retry later; `message` links to the [status page](https://atlorium.com/status). **Not billed** |

All six examples map these codes to human-readable causes — see the `AtloriumError` class.

**About capping the `429` wait.** Once the hourly window is exhausted the server honestly asks you to wait tens of minutes. A client that blindly trusts `Retry-After` will hang for all of it (and burn a whole CI job budget). Hence `MAX_RETRY_DELAY = 120` seconds in the examples: we never wait longer than the cap — we report "quota exhausted" and exit.

## Pricing

**Pay-as-you-go, no subscription** — you pay per successful request. A string with no extractable address (`400`) and the unimplemented combo modes (`501`) are **not billed**.

Current prices: **[atlorium.com/pricing](https://atlorium.com/pricing)**

## FAQ

**How is this different from GAR/FIAS?** They are two different tools and they complement each other. **Standardization** parses an *arbitrary string* into structure and tells you how confident it is — no registry needed. **[GAR/FIAS](https://github.com/atlorium-api/gar-fias-address-api-client)** looks an address up in the *official Russian state address registry* and returns its `objectGuid`, OKTMO code and postal code. In practice you chain them: standardization cleans the dirty string, then GAR/FIAS finds the official object. Automating that chain is exactly what the `bind` flag will do.

**Is this a DaData alternative?** For this job, yes: canonicalizing addresses and parsing them into components with a confidence score. Differences: parsing runs locally on our side, billing is pay-as-you-go with no subscription, and the official address registry is a separate service ([GAR/FIAS](https://github.com/atlorium-api/gar-fias-address-api-client)). You can try it without signing up — the public demo key is already in the examples.

**What does `quality` mean and where should I set the threshold?** It is the engine's confidence in the parse, 0..100. `100` — perfect, `>= 90` — fine, below `80` — be careful. Start at `80`, look at how many addresses land in manual review, and move it: higher threshold means a cleaner database and more operator work; lower means the opposite.

**Do I get a postal code or OKTMO?** Not from this service — it parses a string, it does not reconcile it against a registry. Postal code, OKTMO, OKATO and tax office code come from [GAR/FIAS](https://github.com/atlorium-api/gar-fias-address-api-client) once the address object is found.

**Does it handle non-Russian addresses?** `countryCode` is detected and the parser is not hard-wired to Russia, but Russian addresses are the service's profile and parse quality is noticeably higher on them.

**Is my data sent to third parties?** No. Parsing happens locally, which is why it answers in single-digit milliseconds and does not depend on anyone else's uptime.

**Do I need to sign up to try it?** No. The demo key is public and works without an account — but it returns mocks, not a real parse (see the sandbox section above).

## Other Atlorium APIs

An address rarely travels alone. The same account and the same key also give you:

- [Phone validation](https://github.com/atlorium-api/phone-validation-api-client) — format, line type, range operator
- [GAR/FIAS addresses](https://github.com/atlorium-api/gar-fias-address-api-client) — search and suggestions from the official registry
- [Email verification](https://github.com/atlorium-api/email-verification-api-client) — syntax, MX records, disposable addresses
- [Weather data](https://github.com/atlorium-api/weather-api-client) — current conditions by coordinates
- [Forward geocoding](https://github.com/atlorium-api/geocoding-api-client) — coordinates from an address, batch included
- [Reverse geocoding](https://github.com/atlorium-api/reverse-geocoding-api-client) — address from coordinates, neighbours and landmarks

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/addressstdAPI](https://atlorium.com/addressstdAPI)
- **OpenAPI spec:** [addressstd_en-US.json](https://atlorium.com/openapi/addressstd_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
