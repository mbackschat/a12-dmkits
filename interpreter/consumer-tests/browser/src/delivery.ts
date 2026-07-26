import {
  ModelSourceProviderError,
  type ModelSourceLoadContext,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

export interface ShowcaseManifestModel {
  readonly modelId: string;
  readonly path: string;
  readonly sha256: string;
}

export interface ShowcaseManifest {
  readonly schemaVersion: 1;
  readonly revision: string;
  readonly models: readonly ShowcaseManifestModel[];
}

const INDEX_CACHE = "a12-interpreter-showcase-index";
const MODEL_CACHE_PREFIX = "a12-interpreter-showcase-models:";
const SHELL_CACHE_PREFIX = "a12-interpreter-showcase-shell:";
const SHA256 = /^[0-9a-f]{64}$/;
const SAFE_PATH = /^(?:[a-zA-Z0-9._-]+\/)*[a-zA-Z0-9._-]+\.json$/;

const publicUrl = (path: string): string =>
  new URL(path, new URL(import.meta.env.BASE_URL, location.href)).href;

const networkResponse = async (
  url: string,
  signal?: AbortSignal,
): Promise<Response> => {
  const request: RequestInit = {
    cache: "no-store",
    credentials: "omit",
    ...(signal === undefined ? {} : {signal}),
  };
  const response = await fetch(url, request);
  if (!response.ok) {
    throw new Error(`public resource failed with HTTP ${response.status}`);
  }
  return response;
};

const validateManifest = (value: unknown): ShowcaseManifest => {
  if (value === null || typeof value !== "object") {
    throw new Error("showcase manifest must be an object");
  }
  const candidate = value as Partial<ShowcaseManifest>;
  if (
    candidate.schemaVersion !== 1 ||
    typeof candidate.revision !== "string" ||
    !SHA256.test(candidate.revision) ||
    !Array.isArray(candidate.models) ||
    candidate.models.length === 0
  ) {
    throw new Error("showcase manifest header is invalid");
  }
  const modelIds = new Set<string>();
  for (const model of candidate.models) {
    if (
      model === null ||
      typeof model !== "object" ||
      typeof model.modelId !== "string" ||
      model.modelId.length === 0 ||
      modelIds.has(model.modelId) ||
      typeof model.path !== "string" ||
      !SAFE_PATH.test(model.path) ||
      model.path.split("/").includes("..") ||
      typeof model.sha256 !== "string" ||
      !SHA256.test(model.sha256)
    ) {
      throw new Error("showcase manifest contains an invalid model entry");
    }
    modelIds.add(model.modelId);
  }
  return candidate as ShowcaseManifest;
};

const removeStaleRevisionCaches = async (revision: string): Promise<void> => {
  const retained = new Set([
    INDEX_CACHE,
    `${MODEL_CACHE_PREFIX}${revision}`,
    `${SHELL_CACHE_PREFIX}${revision}`,
  ]);
  await Promise.all((await caches.keys())
    .filter((name) =>
      (name.startsWith(MODEL_CACHE_PREFIX) ||
        name.startsWith(SHELL_CACHE_PREFIX)) &&
      !retained.has(name))
    .map((name) => caches.delete(name)));
};

export const loadShowcaseManifest = async (): Promise<ShowcaseManifest> => {
  const url = publicUrl("showcase-manifest.json");
  const index = await caches.open(INDEX_CACHE);
  let response: Response;
  let fromNetwork = false;
  try {
    response = await networkResponse(url);
    fromNetwork = true;
  } catch {
    const cached = await index.match(url);
    if (cached === undefined) {
      throw new Error("showcase manifest is unavailable online and offline");
    }
    response = cached;
  }
  const manifest = validateManifest(await response.clone().json());
  if (fromNetwork) {
    await index.put(url, response);
  }
  await removeStaleRevisionCaches(manifest.revision);
  return manifest;
};

const sha256 = async (bytes: ArrayBuffer): Promise<string> =>
  Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (value) => value.toString(16).padStart(2, "0"),
  ).join("");

const sourceBytes = async (
  response: Response,
  model: ShowcaseManifestModel,
  maximumBytes: number,
): Promise<ArrayBuffer> => {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) {
    throw new ModelSourceProviderError("source-too-large", model.modelId);
  }
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength > maximumBytes) {
    throw new ModelSourceProviderError("source-too-large", model.modelId);
  }
  if (await sha256(bytes) !== model.sha256) {
    throw new ModelSourceProviderError("integrity-mismatch", model.modelId);
  }
  return bytes;
};

export class BundledWorkspaceDelivery {
  readonly #manifest: ShowcaseManifest;

  constructor(manifest: ShowcaseManifest) {
    this.#manifest = manifest;
  }

  get revision(): string {
    return this.#manifest.revision;
  }

  async load(
    modelId: string,
    context: ModelSourceLoadContext,
  ): Promise<WorkspaceModelSource | undefined> {
    const model = this.#manifest.models.find((entry) =>
      entry.modelId === modelId);
    if (model === undefined) {
      return undefined;
    }
    if (context.signal.aborted) {
      throw new ModelSourceProviderError("cancelled", modelId);
    }
    const url = publicUrl(model.path);
    const cache = await caches.open(
      `${MODEL_CACHE_PREFIX}${this.revision}`,
    );
    let response: Response;
    let fromNetwork = false;
    try {
      response = await networkResponse(url, context.signal);
      fromNetwork = true;
    } catch (error) {
      if (context.signal.aborted) {
        throw new ModelSourceProviderError("cancelled", modelId, {
          cause: error,
        });
      }
      const cached = await cache.match(url);
      if (cached === undefined) {
        throw new ModelSourceProviderError("unavailable", modelId, {
          cause: error,
        });
      }
      response = cached;
    }
    const bytes = await sourceBytes(
      response.clone(),
      model,
      context.maximumBytes,
    );
    if (fromNetwork) {
      await cache.put(
        url,
        new Response(bytes, {
          headers: {"content-type": "application/json; charset=utf-8"},
        }),
      );
    }
    let json: string;
    try {
      json = new TextDecoder("utf-8", {fatal: true}).decode(bytes);
    } catch (error) {
      throw new ModelSourceProviderError("provider-failed", modelId, {
        cause: error,
      });
    }
    return {
      json,
      diagnosticLabel: model.path,
      expectedSha256: model.sha256,
    };
  }

  async loadAll(): Promise<readonly WorkspaceModelSource[]> {
    const controller = new AbortController();
    return Promise.all(this.#manifest.models.map(async ({modelId}) => {
      const source = await this.load(modelId, {
        maximumBytes: 16 * 1024 * 1024,
        signal: controller.signal,
      });
      if (source === undefined) {
        throw new Error(`manifest model '${modelId}' is unavailable`);
      }
      return source;
    }));
  }
}

const controlledByServiceWorker = async (): Promise<void> => {
  if (navigator.serviceWorker.controller !== null) {
    return;
  }
  await new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(
      () => reject(new Error("service worker did not take control")),
      5_000,
    );
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      window.clearTimeout(timeout);
      resolve();
    }, {once: true});
  });
};

export const prepareOfflineShell = async (
  revision: string,
  examplePaths: readonly string[],
): Promise<void> => {
  if (!("serviceWorker" in navigator) || !("caches" in globalThis)) {
    throw new Error("this browser does not support offline Cache Storage");
  }
  await navigator.serviceWorker.register(
    publicUrl("service-worker.js"),
    {scope: import.meta.env.BASE_URL},
  );
  await navigator.serviceWorker.ready;
  await controlledByServiceWorker();

  const base = new URL(import.meta.env.BASE_URL, location.href);
  const shellUrls = new Set<string>([
    base.href,
    publicUrl("release.json"),
    publicUrl("bundle-size.json"),
    publicUrl("showcase-manifest.json"),
  ]);
  examplePaths.forEach((path) => shellUrls.add(publicUrl(path)));
  for (const entry of performance.getEntriesByType("resource")) {
    const url = new URL(entry.name);
    if (
      url.origin === base.origin &&
      url.pathname.startsWith(base.pathname) &&
      url.pathname.includes("/assets/")
    ) {
      shellUrls.add(url.href);
    }
  }
  const shell = await caches.open(`${SHELL_CACHE_PREFIX}${revision}`);
  await shell.addAll(Array.from(shellUrls));
  await removeStaleRevisionCaches(revision);
};
