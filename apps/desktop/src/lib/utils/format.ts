export const formatModelSize = (sizeMb: number | null | undefined): string => {
  if (!sizeMb || !Number.isFinite(sizeMb) || sizeMb <= 0) {
    return "Unknown size";
  }

  if (sizeMb >= 1024) {
    const sizeGb = sizeMb / 1024;
    const formatter = new Intl.NumberFormat(undefined, {
      minimumFractionDigits: sizeGb >= 10 ? 0 : 1,
      maximumFractionDigits: sizeGb >= 10 ? 0 : 1,
    });
    return `${formatter.format(sizeGb)} GB`;
  }

  const formatter = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: sizeMb >= 100 ? 0 : 1,
    maximumFractionDigits: sizeMb >= 100 ? 0 : 1,
  });

  return `${formatter.format(sizeMb)} MB`;
};

// shows people which service they are talking to without the full URL
export const hostOf = (url: string): string => {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
};

export const isLoopbackEndpoint = (url: string): boolean => {
  try {
    const { hostname } = new URL(url);
    return ["localhost", "127.0.0.1", "[::1]"].includes(hostname);
  } catch {
    return false;
  }
};
