#include <brotli/decode.h>
#include <stdint.h>
#include <stdlib.h>

/* Decode one Brotli payload into an unbounded, dynamically growing buffer. */
static inline int lynx_brotli_decode(const uint8_t *input, size_t input_size,
                                     uint8_t **output, size_t *output_size) {
  BrotliDecoderState *state = BrotliDecoderCreateInstance(NULL, NULL, NULL);
  if (state == NULL) return -1;

  size_t capacity = input_size > 8192 ? input_size * 2 : 16384;
  if (capacity < input_size) {
    BrotliDecoderDestroyInstance(state);
    return -1;
  }
  uint8_t *buffer = (uint8_t *)malloc(capacity);
  if (buffer == NULL) {
    BrotliDecoderDestroyInstance(state);
    return -1;
  }

  size_t available_input = input_size;
  const uint8_t *next_input = input;
  size_t available_output = capacity;
  uint8_t *next_output = buffer;
  size_t total_output = 0;
  BrotliDecoderResult result;
  while (1) {
    result = BrotliDecoderDecompressStream(state, &available_input, &next_input,
                                            &available_output, &next_output,
                                            &total_output);
    if (result == BROTLI_DECODER_RESULT_SUCCESS) break;
    if (result == BROTLI_DECODER_RESULT_ERROR) break;
    if (result == BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT || available_output == 0) {
      if (capacity > SIZE_MAX / 2) {
        result = BROTLI_DECODER_RESULT_ERROR;
        break;
      }
      capacity *= 2;
      uint8_t *grown = (uint8_t *)realloc(buffer, capacity);
      if (grown == NULL) {
        result = BROTLI_DECODER_RESULT_ERROR;
        break;
      }
      buffer = grown;
      next_output = buffer + total_output;
      available_output = capacity - total_output;
      continue;
    }
    if (result == BROTLI_DECODER_RESULT_NEEDS_MORE_INPUT && available_input == 0) {
      result = BROTLI_DECODER_RESULT_ERROR;
      break;
    }
  }

  BrotliDecoderDestroyInstance(state);
  if (result != BROTLI_DECODER_RESULT_SUCCESS) {
    free(buffer);
    return -1;
  }

  *output = buffer;
  *output_size = total_output;
  return 0;
}

static inline void lynx_brotli_free(void *buffer) { free(buffer); }
