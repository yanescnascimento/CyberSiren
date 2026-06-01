package com.cybersiren.android.noise.southernstorm.protocol;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

public interface CipherState extends Destroyable {

	String getCipherName();

	int getKeyLength();

	int getMACLength();

	void initializeKey(byte[] key, int offset);

	boolean hasKey();

	int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException;

	int decryptWithAd(byte[] ad, byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) throws ShortBufferException, BadPaddingException;

	CipherState fork(byte[] key, int offset);

	void setNonce(long nonce);
}
