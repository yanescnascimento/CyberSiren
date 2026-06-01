package com.cybersiren.android.noise.southernstorm.protocol;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.cybersiren.android.noise.southernstorm.crypto.GHASH;

class AESGCMOnCtrCipherState implements CipherState {

	private Cipher cipher;
	private SecretKeySpec keySpec;
	private long n;
	private byte[] iv;
	private byte[] hashKey;
	private GHASH ghash;

	public AESGCMOnCtrCipherState() throws NoSuchAlgorithmException
	{
		try {
			cipher = Cipher.getInstance("AES/CTR/NoPadding");
		} catch (NoSuchPaddingException e) {

			throw new NoSuchAlgorithmException("AES/CTR/NoPadding not available", e);
		}
		keySpec = null;
		n = 0;
		iv = new byte [16];
		hashKey = new byte [16];
		ghash = new GHASH();

		try {
			SecretKeySpec spec = new SecretKeySpec(new byte [32], "AES");
			IvParameterSpec params = new IvParameterSpec(iv);
			cipher.init(Cipher.ENCRYPT_MODE, spec, params);
		} catch (InvalidKeyException e) {
			throw new NoSuchAlgorithmException("AES/CTR/NoPadding does not support 256-bit keys", e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new NoSuchAlgorithmException("AES/CTR/NoPadding does not support 256-bit keys", e);
		}
	}

	@Override
	public void destroy() {

		ghash.destroy();
		Noise.destroy(hashKey);
		Noise.destroy(iv);
		keySpec = new SecretKeySpec(new byte [32], "AES");
		IvParameterSpec params = new IvParameterSpec(iv);
		try {
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, params);
		} catch (InvalidKeyException e) {

		} catch (InvalidAlgorithmParameterException e) {

		}
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
		return keySpec != null ? 16 : 0;
	}

	@Override
	public void initializeKey(byte[] key, int offset) {

		keySpec = new SecretKeySpec(key, offset, 32, "AES");

		Arrays.fill(iv, (byte)0);
		Arrays.fill(hashKey, (byte)0);
		try {
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
		} catch (InvalidKeyException e) {

			throw new IllegalStateException(e);
		} catch (InvalidAlgorithmParameterException e) {

			throw new IllegalStateException(e);
		}
		try {
			int result = cipher.update(hashKey, 0, 16, hashKey, 0);
			cipher.doFinal(hashKey, result);
		} catch (ShortBufferException e) {

			throw new IllegalStateException(e);
		} catch (IllegalBlockSizeException e) {

			throw new IllegalStateException(e);
		} catch (BadPaddingException e) {

			throw new IllegalStateException(e);
		}
		ghash.reset(hashKey, 0);

		n = 0;
	}

	@Override
	public boolean hasKey() {
		return keySpec != null;
	}

	private void setup(byte[] ad) throws InvalidKeyException, InvalidAlgorithmParameterException
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

		cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));

		Arrays.fill(hashKey, (byte)0);
		try {
			cipher.update(hashKey, 0, 16, hashKey, 0);
		} catch (ShortBufferException e) {

			throw new IllegalStateException(e);
		}

		ghash.reset();
		if (ad != null) {
			ghash.update(ad, 0, ad.length);
			ghash.pad();
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
		if (keySpec == null) {

			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
			return length;
		}
		if (space < 16 || length > (space - 16))
			throw new ShortBufferException();
		try {
			setup(ad);
			int result = cipher.update(plaintext, plaintextOffset, length, ciphertext, ciphertextOffset);
			cipher.doFinal(ciphertext, ciphertextOffset + result);
		} catch (InvalidKeyException e) {

			throw new IllegalStateException(e);
		} catch (InvalidAlgorithmParameterException e) {

			throw new IllegalStateException(e);
		} catch (IllegalBlockSizeException e) {

			throw new IllegalStateException(e);
		} catch (BadPaddingException e) {

			throw new IllegalStateException(e);
		}
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
		if (keySpec == null) {

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
		try {
			setup(ad);
		} catch (InvalidKeyException e) {

			throw new IllegalStateException(e);
		} catch (InvalidAlgorithmParameterException e) {

			throw new IllegalStateException(e);
		}
		ghash.update(ciphertext, ciphertextOffset, dataLen);
		ghash.pad(ad != null ? ad.length : 0, dataLen);
		ghash.finish(iv, 0, 16);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (hashKey[index] ^ iv[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		try {
			int result = cipher.update(ciphertext, ciphertextOffset, dataLen, plaintext, plaintextOffset);
			cipher.doFinal(plaintext, plaintextOffset + result);
		} catch (IllegalBlockSizeException e) {

			throw new IllegalStateException(e);
		} catch (BadPaddingException e) {

			throw new IllegalStateException(e);
		}
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher;
		try {
			cipher = new AESGCMOnCtrCipherState();
		} catch (NoSuchAlgorithmException e) {

			return null;
		}
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}
}
