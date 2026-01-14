/**
 * Validates that a URL is a properly formatted HTTP or HTTPS URL.
 *
 * @param url - The URL string to validate
 * @returns true if the URL is valid http or https, false otherwise
 */
export function validatePlaylistUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    const isValid = parsed.protocol === "http:" || parsed.protocol === "https:";

    // Security warning for HTTP (not HTTPS)
    if (isValid && parsed.protocol === "http:") {
      console.warn(
        "SECURITY WARNING: Using HTTP (not HTTPS) for playlist URL. " +
          "HTTP connections are vulnerable to man-in-the-middle attacks, credential interception, " +
          "and content tampering. Consider using HTTPS for secure streaming.",
      );
    }

    return isValid;
  } catch {
    return false;
  }
}
