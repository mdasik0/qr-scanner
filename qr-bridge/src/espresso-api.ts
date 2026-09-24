const DEFAULT_API = "http://127.0.0.1:5011/api/v1";

export type ApiMeta = {
  page?: number;
  limit?: number;
  total?: number;
  totalPages?: number;
  hasNext?: boolean;
};

type Envelope<T> = {
  success?: boolean;
  message?: string;
  data?: T;
  meta?: ApiMeta;
};

let cachedToken: string | null = null;

function apiBase(): string {
  return (process.env.ESPRESSO_API_URL || DEFAULT_API).replace(/\/+$/, "");
}

function join(path: string): string {
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  return `${apiBase()}${path.startsWith("/") ? path : `/${path}`}`;
}

function readMessage(json: Envelope<unknown> | null, status: number): string {
  return json?.message || `Espresso API HTTP ${status}`;
}

async function login(): Promise<string> {
  const identifier =
    process.env.ESPRESSO_LOOKUP_IDENTIFIER || "wearhouse@espresso.com";
  const password = process.env.ESPRESSO_LOOKUP_PASSWORD || "123456";

  const res = await fetch(join("/login"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ identifier, password }),
  });
  const json = (await res.json().catch(() => null)) as Envelope<{
    accessToken?: string;
  }> | null;
  const token = json?.data?.accessToken;
  if (!res.ok || !token) {
    throw new Error(readMessage(json, res.status) || "Espresso login failed");
  }
  cachedToken = token;
  return token;
}

async function token(): Promise<string> {
  return cachedToken ?? login();
}

export async function espressoGet<T>(
  path: string,
  query?: Record<string, string | number | undefined>
): Promise<{ data: T; meta?: ApiMeta; status: number }> {
  const url = new URL(join(path));
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    }
  }

  const run = async (auth: string) =>
    fetch(url, {
      headers: {
        Authorization: `Bearer ${auth}`,
        Accept: "application/json",
      },
    });

  let auth = await token();
  let res = await run(auth);
  if (res.status === 401) {
    cachedToken = null;
    auth = await login();
    res = await run(auth);
  }

  const json = (await res.json().catch(() => null)) as Envelope<T> | null;
  if (res.status === 404) {
    return { data: undefined as T, meta: json?.meta, status: 404 };
  }
  if (!res.ok) {
    throw new Error(readMessage(json, res.status));
  }
  return { data: json?.data as T, meta: json?.meta, status: res.status };
}

export async function espressoGetAllPages<T>(
  path: string,
  query?: Record<string, string | number | undefined>
): Promise<T[]> {
  const all: T[] = [];
  let page = 1;
  for (;;) {
    const { data, meta } = await espressoGet<T[]>(path, {
      ...query,
      page,
      limit: 100,
    });
    const rows = Array.isArray(data) ? data : [];
    all.push(...rows);
    const totalPages = meta?.totalPages ?? 1;
    const hasNext = meta?.hasNext ?? page < totalPages;
    if (!hasNext || rows.length === 0) break;
    page += 1;
    if (page > 50) break;
  }
  return all;
}
