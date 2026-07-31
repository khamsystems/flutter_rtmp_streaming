import Foundation

/// Renders a publish URL safe to write to the device log.
///
/// The stream key is the last path segment of an RTMP/RTMPS publish URL, and with
/// providers like Cloudflare Stream that segment *is* the whole credential --
/// anyone reading it can publish to the input. Device logs are readable by other
/// tooling and by crash collectors, so the raw URL must never be printed.
///
/// Scheme, host, port and app path are kept because those are the parts that
/// matter when debugging a connect failure. The key is masked to its last four
/// characters: enough to tell which key is in use, not enough to publish with.
/// Any query string is dropped wholesale, since some providers pass auth tokens
/// there.
///
/// Mirrors `redactStreamUrl` in the Android source.
func redactStreamUrl(_ url: String?) -> String {
  guard let url = url, !url.isEmpty else { return "(none)" }

  let withoutQuery = url.components(separatedBy: "?")[0]
  let hadQuery = withoutQuery.count != url.count

  // No path separator at all: we cannot tell URL from key, so keep none of it.
  guard let cut = withoutQuery.lastIndex(of: "/") else { return "<redacted>" }

  let head = String(withoutQuery[withoutQuery.startIndex..<cut])
  let key = String(withoutQuery[withoutQuery.index(after: cut)...])
  let masked = key.count <= 4 ? "****" : "****\(key.suffix(4))"

  return head + "/" + masked + (hadQuery ? " (+query redacted)" : "")
}
