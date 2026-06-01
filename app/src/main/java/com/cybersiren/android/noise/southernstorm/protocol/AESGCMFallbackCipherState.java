package com.cybersiren.android.noise.southernstorm.protocol;

import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import com.cybersiren.android.noise.southernstorm.crypto.GHASH;
import com.cybersiren.android.noise.southernstorm.crypto.RijndaelAES;

class AESGCMFallbackCipherState implements CipherState {

	private RijndaelAES aes;
	private long n;
	private byte[] iv;
	private byte[] enciv;
	private byte[] hashKey;
	private GHASH ghash;
	private boolean haskey;

	public AESGCMFallbackCipherState()
	{
		aes = new RijndaelAES();
		n = 0;
		iv = new byte [16];
		enciv = new byte [16];
		hashKey = new byte [16];
		ghash = new GHASH();
		haskey = false;
	}

	@Override
	public void destroy() {
		aes.destroy();
		ghash.destroy();
		Noise.destroy(hashKey);
		Noise.destroy(iv);
		Noise.destroy(enciv);
	}

	@Override
	public String getCipherName() {
		return "AESGCM";
	}

	@Override
	public int getKeyLength() {
		return 32;
	}

	@Override
	public int getMACLength() {
		return haskey ? 16 : 0;
	}

	@Override
	public void initializeKey(byte[] key, int offset) {

		aes.setupEnc(key, offset, 256);
		haskey = true;

		Arrays.fill(hashKey, (byte)0);
		aes.encrypt(hashKey, 0, hashKey, 0);
		ghash.reset(hashKey, 0);

		n = 0;
	}

	@Override
	public boolean hasKey() {
		return haskey;
	}

	private void setup(byte[] ad)
	{

		if (n == -1L)
			throw new IllegalStateException("Nonce has wrapped around");

		iv[0] = 0;
		iv[1] = 0;
		iv[2] = 0;
		iv[3] = 0;
		iv[4] = (byte)(n >> 56);
		iv[5] = (byte)(n >> 48);
		iv[6] = (byte)(n >> 40);
		iv[7] = (byte)(n >> 32);
		iv[8] = (byte)(n >> 24);
		iv[9] = (byte)(n >> 16);
		iv[10] = (byte)(n >> 8);
		iv[11] = (byte)n;
		iv[12] = 0;
		iv[13] = 0;
		iv[14] = 0;
		iv[15] = 1;
		++n;

		Arrays.fill(hashKey, (byte)0);
		aes.encrypt(iv, 0, hashKey, 0);

		ghash.reset();
		if (ad != null) {
			ghash.update(ad, 0, ad.length);
			ghash.pad();
		}
	}

	private void encryptCTR(byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length)
	{
		while (length > 0) {

			if (++(iv[15]) == 0)
				if (++(iv[14]) == 0)
					if (++(iv[13]) == 0)
						++(iv[12]);
			aes.encrypt(iv, 0, enciv, 0);

			int temp = length;
			if (temp > 16)
				temp = 16;
			for (int index = 0; index < temp; ++index)
				ciphertext[ciphertextOffset + index] = (byte)(plaintext[plaintextOffset + index] ^ enciv[index]);

			plaintextOffset += temp;
			ciphertextOffset += temp;
			length -= temp;
		}
	}

	@Override
	public int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length)
			throws ShortBufferException {
		int space;
		if (ciphertextOffset < 0 || ciphertextOffset > ciphertext.length)
			throw new IllegalArgumentException();
    if (length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.length || length > plaintext.length || (plaintext.length - plaintextOffset) < length)
			throw new IllegalArgumentException();
		space = ciphertext.length - ciphertextOffset;
		if (!haskey) {

			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
			return length;
		}
		if (space < 16 || length > (space - 16))
			throw new ShortBufferException();
		setup(ad);
		encryptCTR(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		ghash.update(ciphertext, ciphertextOffset, length);
		ghash.pad(ad != null ? ad.length : 0, length);
		ghash.finish(ciphertext, ciphertextOffset + length, 16);
		for (int index = 0; index < 16; ++index)
			ciphertext[ciphertextOffset + length + index] ^= hashKey[index];
		return length + 16;
	}

	@Override
	public int decryptWithAd(byte[] ad, byte[] ciphertext,
			int ciphertextOffset, byte[] plaintext, int plaintextOffset,
			int length) throws ShortBufferException, BadPaddingException {
		int space;
		if (ciphertextOffset < 0 || ciphertextOffset > ciphertext.length)
			throw new IllegalArgumentException();
		else
			space = ciphertext.length - ciphertextOffset;
		if (length > space)
			throw new ShortBufferException();
		if (length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.length || length > ciphertext.length || (ciphertext.length - ciphertextOffset) < length)
			throw new IllegalArgumentException();
		space = plaintext.length - plaintextOffset;
		if (!haskey) {

			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
			return length;
		}
		if (length < 16)
			Noise.throwBadTagException();
		int dataLen = length - 16;
		if (dataLen > space)
			throw new ShortBufferException();
		setup(ad);
		ghash.update(ciphertext, ciphertextOffset, dataLen);
		ghash.pad(ad != null ? ad.length : 0, dataLen);
		ghash.finish(enciv, 0, 16);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (hashKey[index] ^ enciv[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		encryptCTR(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen);
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher;
		cipher = new AESGCMFallbackCipherState();
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}
}
