package com.cybersiren.android.noise.southernstorm.protocol;

import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import com.cybersiren.android.noise.southernstorm.crypto.ChaChaCore;
import com.cybersiren.android.noise.southernstorm.crypto.Poly1305;

class ChaChaPolyCipherState implements CipherState {

	private Poly1305 poly;
	private int[] input;
	private int[] output;
	private byte[] polyKey;
	long n;
	private boolean haskey;

	public ChaChaPolyCipherState()
	{
		poly = new Poly1305();
		input = new int [16];
		output = new int [16];
		polyKey = new byte [32];
		n = 0;
		haskey = false;
	}

	@Override
	public void destroy() {
		poly.destroy();
		Arrays.fill(input, 0);
		Arrays.fill(output, 0);
		Noise.destroy(polyKey);
	}

	@Override
	public String getCipherName() {
		return "ChaChaPoly";
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
		ChaChaCore.initKey256(input, key, offset);
		n = 0;
		haskey = true;
	}

	@Override
	public boolean hasKey() {
		return haskey;
	}

	private static void xorBlock(byte[] input, int inputOffset, byte[] output, int outputOffset, int length, int[] block)
	{
		int posn = 0;
		int value;
		while (length >= 4) {
			value = block[posn++];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
			output[outputOffset + 2] = (byte)(input[inputOffset + 2] ^ (value >> 16));
			output[outputOffset + 3] = (byte)(input[inputOffset + 3] ^ (value >> 24));
			inputOffset += 4;
			outputOffset += 4;
			length -= 4;
		}
		if (length == 3) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
			output[outputOffset + 2] = (byte)(input[inputOffset + 2] ^ (value >> 16));
		} else if (length == 2) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
		} else if (length == 1) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
		}
	}

	private void setup(byte[] ad)
	{
		if (n == -1L)
			throw new IllegalStateException("Nonce has wrapped around");
		ChaChaCore.initIV(input, n++);
		ChaChaCore.hash(output, input);
		Arrays.fill(polyKey, (byte)0);
		xorBlock(polyKey, 0, polyKey, 0, 32, output);
		poly.reset(polyKey, 0);
		if (ad != null) {
			poly.update(ad, 0, ad.length);
			poly.pad();
		}
		if (++(input[12]) == 0)
			++(input[13]);
	}

	private static void putLittleEndian64(byte[] output, int offset, long value)
	{
		output[offset] = (byte)value;
		output[offset + 1] = (byte)(value >> 8);
		output[offset + 2] = (byte)(value >> 16);
		output[offset + 3] = (byte)(value >> 24);
		output[offset + 4] = (byte)(value >> 32);
		output[offset + 5] = (byte)(value >> 40);
		output[offset + 6] = (byte)(value >> 48);
		output[offset + 7] = (byte)(value >> 56);
	}

	private void finish(byte[] ad, int length)
	{
		poly.pad();
		putLittleEndian64(polyKey, 0, ad != null ? ad.length : 0);
		putLittleEndian64(polyKey, 8, length);
		poly.update(polyKey, 0, 16);
		poly.finish(polyKey, 0);
	}

	private void encrypt(byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length) {
		while (length > 0) {
			int tempLen = 64;
			if (tempLen > length)
				tempLen = length;
			ChaChaCore.hash(output, input);
			xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
			if (++(input[12]) == 0)
				++(input[13]);
			plaintextOffset += tempLen;
			ciphertextOffset += tempLen;
			length -= tempLen;
		}
	}

	@Override
	public int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException {
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
		encrypt(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		poly.update(ciphertext, ciphertextOffset, length);
		finish(ad, length);
		System.arraycopy(polyKey, 0, ciphertext, ciphertextOffset + length, 16);
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
		poly.update(ciphertext, ciphertextOffset, dataLen);
		finish(ad, dataLen);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (polyKey[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		encrypt(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen);
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher = new ChaChaPolyCipherState();
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}
}
