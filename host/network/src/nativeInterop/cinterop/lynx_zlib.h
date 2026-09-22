#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

/* Decompress one gzip member into a dynamically growing buffer. The caller owns
 * the returned allocation and releases it with lynx_gunzip_free. */
static inline int lynx_gunzip(const uint8_t *input, size_t input_size,
                              uint8_t **output, size_t *output_size) {
  if (input_size > UINT_MAX) return -1;

  z_stream stream;
  memset(&stream, 0, sizeof(stream));
  stream.next_in = (Bytef *)input;
  stream.avail_in = (uInt)input_size;
  if (inflateInit2(&stream, 15 + 16) != Z_OK) return -1;

  size_t capacity = input_size > 8192 ? input_size * 2 : 16384;
  if (capacity < input_size) {
    inflateEnd(&stream);
    return -1;
  }
  uint8_t *buffer = (uint8_t *)malloc(capacity);
  if (buffer == NULL) {
    inflateEnd(&stream);
    return -1;
  }

  int result = Z_OK;
  while (result == Z_OK) {
    if ((size_t)stream.total_out == capacity) {
      if (capacity > SIZE_MAX / 2) {
        result = Z_MEM_ERROR;
        break;
      }
      capacity *= 2;
      uint8_t *grown = (uint8_t *)realloc(buffer, capacity);
      if (grown == NULL) {
        result = Z_MEM_ERROR;
        break;
      }
      buffer = grown;
    }
    stream.next_out = buffer + stream.total_out;
    size_t available = capacity - (size_t)stream.total_out;
    stream.avail_out = (uInt)(available > UINT_MAX ? UINT_MAX : available);
    result = inflate(&stream, Z_NO_FLUSH);
    if (result == Z_OK && stream.avail_in == 0 && stream.avail_out != 0) {
      result = Z_DATA_ERROR;
    }
  }

  if (result != Z_STREAM_END) {
    free(buffer);
    inflateEnd(&stream);
    return -1;
  }

  *output = buffer;
  *output_size = (size_t)stream.total_out;
  inflateEnd(&stream);
  return 0;
}

static inline void lynx_gunzip_free(void *buffer) { free(buffer); }
