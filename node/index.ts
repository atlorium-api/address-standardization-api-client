/**
 * Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
 * в структуру: страна / город / улица / дом / квартира + нормализованное написание
 * и коэффициент качества разбора.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *   npm start -- "мск ленинскй проспект д4 кв5"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

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

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

const TIMEOUT_MS = 30_000;

/** Пауза перед повтором после 429 и число повторов. */
const RETRY_DELAY_S = 20;
const MAX_RETRIES = 1;

/**
 * Потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит подождать десятки
 * минут — и клиент, слепо доверяющий заголовку Retry-After, «зависнет» на всё это
 * время (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
 */
const MAX_RETRY_DELAY_S = 120;

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

/** Адресные уровни, сверху вниз — от страны к комнате. */
export type AddressLevel =
  | 'Undefined'
  | 'Country'
  | 'RegionArea'
  | 'RegionCity'
  | 'District'
  | 'Settlement'
  | 'City'
  | 'CityDistrict'
  | 'Locality'
  | 'Territory'
  | 'Street'
  | 'Plot'
  | 'Building'
  | 'Apartment'
  | 'Room';

/** Один разобранный элемент адреса. */
export interface AddressComponent {
  level: AddressLevel;
  /** Название уровня по-русски: «Улица», «Дом/здание», … */
  levelTitle: string;
  /** Нормализованное представление элемента одной строкой: «улица 16 Парковая». */
  text: string;
  /** Тип элемента: ['улица'], ['город']. */
  types: string[] | null;
  /** Собственные имена: ['16', 'Парковая']. */
  names: string[] | null;
  /** Основной номер: дома, участка, квартиры, комнаты. */
  number: string | null;
  /** Корпус — только для домов. */
  buildNumber: string | null;
  /** Строение — только для домов. */
  structNumber: string | null;
  /** Номер земельного участка. */
  plotNumber: string | null;
  /** Второстепенные уточнения. */
  misc: string[] | null;
}

/** Итог стандартизации одной адресной строки. */
export interface StandardizedAddress {
  /** Эхо запроса: строка, как её прислали. */
  input: string;
  /** Нормализованный адрес одной строкой. */
  normalized: string;
  /** Коэффициент качества разбора, 0..100. */
  quality: number;
  countryCode: string | null;
  /** Сообщение о неточности, ВЛИЯЮЩЕЙ на качество. */
  message: string | null;
  /** Информационное сообщение, НЕ влияющее на качество. */
  info: string | null;
  components: AddressComponent[];
  /** Элементы, не вписавшиеся в модель: этаж, а/я и т.п. */
  extra: Record<string, string> | null;
  /** Время разбора, мс. */
  milliseconds: number;
  /** Привязка к ГАР/ФИАС. Задел: сейчас всегда null (комбо-режим в разработке). */
  gar: unknown | null;
  /** Координаты. Задел: сейчас всегда null (комбо-режим в разработке). */
  geo: unknown | null;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  429: 'Превышен лимит запросов — повторите позже',
  501: 'Комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован',
  503: 'Сервис временно недоступен (плановые работы) — повторите позже',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

const sleep = (seconds: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, seconds * 1000));

/**
 * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
 * 0 означало бы busy-loop, а «40 минут» (так сервер отвечает на исчерпанное часовое
 * окно) — зависание клиента. Возвращаем 0, если ждать бессмысленно долго.
 */
function retryAfter(response: Response): number {
  const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return RETRY_DELAY_S;
  return seconds <= MAX_RETRY_DELAY_S ? seconds : 0;
}

/**
 * Разбор адресной строки: GET /api/addressstd?address=...
 *
 * `region` — код региона по умолчанию (например, 77), если в строке региона нет.
 * Разбор выполняется локально, без обращений к внешним источникам, — отсюда время
 * ответа в единицы миллисекунд (см. поле `milliseconds`).
 */
export async function standardize(address: string, region?: number): Promise<StandardizedAddress> {
  const url = new URL('/api/addressstd', BASE_URL);
  url.searchParams.set('address', address);
  if (region !== undefined) url.searchParams.set('region', String(region));

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        Accept: 'application/json',
      },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
    if (response.status === 429 && attempt < MAX_RETRIES) {
      const delay = retryAfter(response);
      if (delay === 0) {
        throw new AtloriumError(429, 'лимит ключа по IP исчерпан, повторите позже');
      }
      console.error(`  ... лимит запросов, пауза ${delay} с`);
      await sleep(delay);
      continue;
    }

    if (!response.ok) {
      throw new AtloriumError(response.status, await response.text());
    }
    return (await response.json()) as StandardizedAddress;
  }

  throw new AtloriumError(429, 'лимит запросов не отпустил после повтора');
}

// ── Применение данных: чистка адресов в CRM ───────────────────────────────────
// Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
// принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
// должен посмотреть человек.
//
// Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
// всё сомнительное эскалируем, а не портим базу молча.

export const ACCEPTED = 'ПРИНЯТ';
export const MANUAL = 'НА ПРОВЕРКУ';

/** Строка адресной базы после чистки — то, что уходит в колонки CRM. */
export interface CleanedAddress {
  source: string;
  normalized: string;
  quality: number;
  country: string;
  city: string;
  street: string;
  house: string;
  apartment: string;
  decision: string;
  reason: string;
  elapsedMs: number;
}

function componentOf(parsed: StandardizedAddress, level: AddressLevel): AddressComponent | undefined {
  return (parsed.components ?? []).find((component) => component.level === level);
}

/**
 * Собственное имя элемента: «Тверская» вместо «улица Тверская».
 * `names` — разобранные имена, `text` — готовое нормализованное написание.
 */
function nameOf(component: AddressComponent | undefined): string {
  if (!component) return '';
  const names = component.names ?? [];
  return names.length > 0 ? names.join(' ') : component.text;
}

/** Номер дома вместе с корпусом и строением: «4 к.1 стр.2». */
function houseOf(component: AddressComponent | undefined): string {
  if (!component) return '';
  const parts = [component.number ?? ''];
  if (component.buildNumber) parts.push(`к.${component.buildNumber}`);
  if (component.structNumber) parts.push(`стр.${component.structNumber}`);
  return parts.filter(Boolean).join(' ');
}

async function cleanOne(source: string): Promise<CleanedAddress> {
  const parsed = await standardize(source);

  const quality = parsed.quality ?? 0;
  const street = nameOf(componentOf(parsed, 'Street'));
  const house = houseOf(componentOf(parsed, 'Building'));

  // ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
  let decision: string;
  let reason: string;

  if (quality < QUALITY_THRESHOLD) {
    decision = MANUAL;
    reason = `качество ${quality} < порога ${QUALITY_THRESHOLD}`;
  } else if (!street || !house) {
    // Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
    // доставить по нему нельзя. Тоже человеку.
    decision = MANUAL;
    reason = 'не выделены улица или дом';
  } else {
    decision = ACCEPTED;
    reason = `качество ${quality} >= порога ${QUALITY_THRESHOLD}`;
  }

  // `message` — сообщение о неточности, влияющей на качество. Готовая подсказка оператору.
  if (parsed.message) reason = `${reason}; ${parsed.message}`;

  return {
    source,
    normalized: parsed.normalized,
    quality,
    country: parsed.countryCode ?? '—',
    city: nameOf(componentOf(parsed, 'City')),
    street,
    house,
    apartment: componentOf(parsed, 'Apartment')?.number ?? '',
    decision,
    reason,
    elapsedMs: parsed.milliseconds ?? 0,
  };
}

/**
 * Чистка адресной базы: каждый адрес разбирается и получает решение.
 * Список намеренно короткий: сервис тарифицируется за запрос.
 */
export async function cleanAddressDatabase(addresses: string[]): Promise<CleanedAddress[]> {
  const rows: CleanedAddress[] = [];
  for (const address of addresses) {
    rows.push(await cleanOne(address));
  }
  return rows;
}

function cut(text: string, width: number): string {
  return text.length <= width ? text : `${text.slice(0, width - 1)}…`;
}

function pad(text: string, width: number): string {
  return text.length >= width ? text : text + ' '.repeat(width - text.length);
}

async function main(): Promise<void> {
  if (API_KEY === SANDBOX_KEY) {
    console.log('Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n');
  }

  // Первый адрес — из аргумента командной строки, остальные два «грязные»:
  // так люди и вводят адреса в реальные формы.
  const primary = process.argv[2] ?? 'г Москва, ул. Тверская, д. 7, кв. 12';
  const addresses = [
    primary,
    'мск ленинскй проспект д4 кв5', // сокращение города + опечатка в улице
    'спб невский пр-кт 28',         // сокращения, нет запятых, нет квартиры
  ];

  console.log(`Чистка адресной базы. Адресов на входе: ${addresses.length}\n`);

  const rows = await cleanAddressDatabase(addresses);

  rows.forEach((row, index) => {
    console.log(`[${index + 1}] «${row.source}»`);
    console.log(`    Нормализовано: ${row.normalized}`);
    console.log(`    Качество: ${row.quality} · Страна: ${row.country} · ${row.elapsedMs} мс`);
    console.log(
      `    Город: ${row.city || '—'} | Улица: ${row.street || '—'} | ` +
        `Дом: ${row.house || '—'} | Кв.: ${row.apartment || '—'}`,
    );
    console.log(`    ${row.decision}: ${row.reason}\n`);
  });

  console.log(`${pad('ИСХОДНАЯ СТРОКА', 34)}${pad('НОРМАЛИЗОВАННЫЙ АДРЕС', 46)}${String('КАЧ').padStart(4)}  РЕШЕНИЕ`);
  for (const row of rows) {
    console.log(
      pad(cut(row.source, 33), 34) +
        pad(cut(row.normalized, 45), 46) +
        String(row.quality).padStart(4) +
        `  ${row.decision}`,
    );
  }

  const accepted = rows.filter((row) => row.decision === ACCEPTED).length;
  const manual = rows.length - accepted;

  console.log('\nИТОГО');
  console.log(`  Обработано адресов:    ${rows.length}`);
  console.log(`  Принято автоматически: ${accepted}`);
  console.log(`  На ручную проверку:    ${manual}`);
  console.log(`\nПорог качества: ${QUALITY_THRESHOLD}. Молча записать криво разобранный адрес в базу`);
  console.log('хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.');

  if (API_KEY === SANDBOX_KEY) {
    console.log('\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —');
    console.log('    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.');
    console.log('    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.');
  }
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().catch((error: unknown) => {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
