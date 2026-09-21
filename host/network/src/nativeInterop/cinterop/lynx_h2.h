#include <nghttp2/nghttp2.h>
#include <string.h>

static inline ssize_t lynx_h2_inflate_text(nghttp2_hd_inflater *inflater,
                                           const uint8_t *input, size_t length,
                                           uint8_t *output, size_t output_length) {
  size_t offset = 0;
  size_t written = 0;
  while (offset < length) {
    nghttp2_nv header;
    int flags = 0;
    ssize_t consumed = nghttp2_hd_inflate_hd2(inflater, &header, &flags,
                                              input + offset, length - offset, 1);
    if (consumed < 0) return consumed;
    if (consumed == 0) break;
    offset += (size_t)consumed;
    if (flags & NGHTTP2_HD_INFLATE_EMIT) {
      size_t needed = header.namelen + 1 + header.valuelen + 1;
      if (written + needed > output_length) return -2;
      memcpy(output + written, header.name, header.namelen);
      written += header.namelen;
      output[written++] = '=';
      memcpy(output + written, header.value, header.valuelen);
      written += header.valuelen;
      output[written++] = '\n';
    }
    if (flags & NGHTTP2_HD_INFLATE_FINAL) break;
  }
  if (nghttp2_hd_inflate_end_headers(inflater) != 0) return -3;
  return (ssize_t)written;
}
