/**
 * Validates that a URL is a properly formatted HTTP or HTTPS URL.
 *
 * @param url - The URL string to validate
 * @returns true if the URL is valid http or https, false otherwise
 */
export function validatePlaylistUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  } catch {
    return false;
  }
}
