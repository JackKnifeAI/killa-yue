/* Turnip references this vendor-only call for Android hardware buffers, which
 * headless compute never uses. A stand-in keeps the driver free of a library
 * that apps may not link. */
struct AHardwareBuffer;
const void *AHardwareBuffer_getNativeHandle(const struct AHardwareBuffer *b) { (void) b; return 0; }
