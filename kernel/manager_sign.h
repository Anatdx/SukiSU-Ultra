#ifndef MANAGER_SIGN_H
#define MANAGER_SIGN_H

// First Manager
#define EXPECTED_SIZE_FIRST 0x302
#define EXPECTED_HASH_FIRST                                                \
    "3376ac8050cdcefc57160449254012a1ef0b0b3885834c12ad952abf01004fa7"

// Second Manager
#define EXPECTED_SIZE_SECOND 0x2d6
#define EXPECTED_HASH_SECOND                                                \
    "8487f808fd8651138c5d718b2c6ffcb713454d6d175b258b11e1225b503b33f9"

typedef struct {
	unsigned size;
	const char *sha256;
} apk_sign_key_t;

#endif /* MANAGER_SIGN_H */
