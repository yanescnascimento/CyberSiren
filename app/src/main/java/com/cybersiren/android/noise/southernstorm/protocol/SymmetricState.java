package com.cybersiren.android.noise.southernstorm.protocol;

import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

class SymmetricState implements Destroyable {

	private static final String TAG = "AndroidSymmetric";

	private String name;
	private CipherState cipher;
	private MessageDigest hash;
	private byte[] ck;
	private byte[] h;
	private byte[] prev_h;

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public SymmetricState(String protocolName, String cipherName, String hashName) throws NoSuchAlgorithmException
	{
		name = protocolName;
		cipher = Noise.createCipher(cipherName);
		hash = Noise.createHash(hashName);
		int hashLength = hash.getDigestLength();
		ck = new byte [hashLength];
		h = new byte [hashLength];
		prev_h = new byte [hashLength];

		byte[] protocolNameBytes;
		try {
			protocolNameBytes = protocolName.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {

			throw new UnsupportedOperationException("UTF-8 encoding is not supported");
		}

		if (protocolNameBytes.length <= hashLength) {
			System.arraycopy(protocolNameBytes, 0, h, 0, protocolNameBytes.length);
			Arrays.fill(h, protocolNameBytes.length, h.length, (byte)0);
		} else {
			hashOne(protocolNameBytes, 0, protocolNameBytes.length, h, 0, h.length);
		}

		System.arraycopy(h, 0, ck, 0, hashLength);

		Log.d(TAG, "=== ANDROID SYMMETRIC STATE INITIALIZED ===");
		Log.d(TAG, "Protocol: " + protocolName);
		Log.d(TAG, "Initial hash (h): " + bytesToHex(h));
		Log.d(TAG, "Initial chaining key (ck): " + bytesToHex(ck));
		Log.d(TAG, "Hash length: " + h.length);
		Log.d(TAG, "=========================================");
	}

	public String getProtocolName()
	{
		return name;
	}

	public int getMACLength()
	{
		return cipher.getMACLength();
	}

	public void mixKey(byte[] data, int offset, int length)
	{

		byte[] inputData = new byte[length];
		System.arraycopy(data, offset, inputData, 0, length);
		Log.d(TAG, "*** Android mixKey() BEFORE ***");
		Log.d(TAG, "Input data (" + length + " bytes): " + bytesToHex(inputData));
		Log.d(TAG, "Current CK: " + bytesToHex(ck));
		Log.d(TAG, "Current Hash: " + bytesToHex(h));

		int keyLength = cipher.getKeyLength();
		byte[] tempKey = new byte [keyLength];
		try {
			hkdf(ck, 0, ck.length, data, offset, length, ck, 0, ck.length, tempKey, 0, keyLength);
			cipher.initializeKey(tempKey, 0);
		} finally {
			Noise.destroy(tempKey);
		}

		Log.d(TAG, "*** Android mixKey() AFTER ***");
		Log.d(TAG, "New CK: " + bytesToHex(ck));
		Log.d(TAG, "Hash unchanged: " + bytesToHex(h));
		Log.d(TAG, "Cipher now has key: " + (cipher.getMACLength() > 0));
	}

	public void mixHash(byte[] data, int offset, int length)
	{

		byte[] inputData = new byte[length];
		System.arraycopy(data, offset, inputData, 0, length);
		Log.d(TAG, "*** Android mixHash() BEFORE ***");
		Log.d(TAG, "Input data (" + length + " bytes): " + bytesToHex(inputData));
		Log.d(TAG, "Current Hash: " + bytesToHex(h));

		hashTwo(h, 0, h.length, data, offset, length, h, 0, h.length);

		Log.d(TAG, "*** Android mixHash() AFTER ***");
		Log.d(TAG, "New Hash: " + bytesToHex(h));
	}

	public void mixPreSharedKey(byte[] key)
	{
		byte[] temp = new byte [hash.getDigestLength()];
		try {
			hkdf(ck, 0, ck.length, key, 0, key.length, ck, 0, ck.length, temp, 0, temp.length);
			mixHash(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	public void mixPublicKey(DHState dh)
	{
		byte[] temp = new byte [dh.getPublicKeyLength()];
		try {
			dh.getPublicKey(temp, 0);
			mixHash(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	public void mixPublicKeyIntoCK(DHState dh)
	{
		byte[] temp = new byte [dh.getPublicKeyLength()];
		try {
			dh.getPublicKey(temp, 0);
			mixKey(temp, 0, temp.length);
		} finally {
			Noise.destroy(temp);
		}
	}

	public int encryptAndHash(byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException
	{
		int ciphertextLength = cipher.encryptWithAd(h, plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		mixHash(ciphertext, ciphertextOffset, ciphertextLength);
		return ciphertextLength;
	}

	public int decryptAndHash(byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) throws ShortBufferException, BadPaddingException
	{
		System.arraycopy(h, 0, prev_h, 0, h.length);
		mixHash(ciphertext, ciphertextOffset, length);
		return cipher.decryptWithAd(prev_h, ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
	}

	public CipherStatePair split()
	{
		return split(new byte[0], 0, 0);
	}

	public CipherStatePair split(byte[] secondaryKey, int offset, int length)
	{
		if (length != 0 && length != 32)
			throw new IllegalArgumentException("Secondary keys must be 0 or 32 bytes in length");
		int keyLength = cipher.getKeyLength();
		byte[] k1 = new byte [keyLength];
		byte[] k2 = new byte [keyLength];
		try {
			hkdf(ck, 0, ck.length, secondaryKey, offset, length, k1, 0, k1.length, k2, 0, k2.length);
			CipherState c1 = null;
			CipherState c2 = null;
			CipherStatePair pair = null;
			try {
				c1 = cipher.fork(k1, 0);
				c2 = cipher.fork(k2, 0);
				pair = new CipherStatePair(c1, c2);
			} finally {
				if (c1 == null || c2 == null || pair == null) {

					if (c1 != null)
						c1.destroy();
					if (c2 != null)
						c2.destroy();
					pair = null;
				}
			}
			return pair;
		} finally {
			Noise.destroy(k1);
			Noise.destroy(k2);
		}
	}

	public byte[] getHandshakeHash()
	{
		return h;
	}

	@Override
	public void destroy() {
		if (cipher != null) {
			cipher.destroy();
			cipher = null;
		}
		if (hash != null) {

			if (hash instanceof Destroyable)
				((Destroyable)hash).destroy();
			else
				hash.reset();
			hash = null;
		}
		if (ck != null) {
			Noise.destroy(ck);
			ck = null;
		}
		if (h != null) {
			Noise.destroy(h);
			h = null;
		}
		if (prev_h != null) {
			Noise.destroy(prev_h);
			prev_h = null;
		}
	}

	private void hashOne(byte[] data, int offset, int length, byte[] output, int outputOffset, int outputLength)
	{
		hash.reset();
		hash.update(data, offset, length);
		try {
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		}
	}

	private void hashTwo(byte[] data1, int offset1, int length1,
			     		 byte[] data2, int offset2, int length2,
			     		 byte[] output, int outputOffset, int outputLength)
	{
		hash.reset();
		hash.update(data1, offset1, length1);
		hash.update(data2, offset2, length2);
		try {
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		}
	}

	private void hmac(byte[] key, int keyOffset, int keyLength,
					  byte[] data, int dataOffset, int dataLength,
					  byte[] output, int outputOffset, int outputLength)
	{

		int hashLength = hash.getDigestLength();
		int blockLength = hashLength * 2;
		byte[] block = new byte [blockLength];
		int index;
		try {
			if (keyLength <= blockLength) {
				System.arraycopy(key, keyOffset, block, 0, keyLength);
				Arrays.fill(block, keyLength, blockLength, (byte)0);
			} else {
				hash.reset();
				hash.update(key, keyOffset, keyLength);
				hash.digest(block, 0, hashLength);
				Arrays.fill(block, hashLength, blockLength, (byte)0);
			}
			for (index = 0; index < blockLength; ++index)
				block[index] ^= (byte)0x36;
			hash.reset();
			hash.update(block, 0, blockLength);
			hash.update(data, dataOffset, dataLength);
			hash.digest(output, outputOffset, hashLength);
			for (index = 0; index < blockLength; ++index)
				block[index] ^= (byte)(0x36 ^ 0x5C);
			hash.reset();
			hash.update(block, 0, blockLength);
			hash.update(output, outputOffset, hashLength);
			hash.digest(output, outputOffset, outputLength);
		} catch (DigestException e) {
			Arrays.fill(output, outputOffset, outputLength, (byte)0);
		} finally {
			Noise.destroy(block);
		}
	}

	private void hkdf(byte[] key, int keyOffset, int keyLength,
			  		  byte[] data, int dataOffset, int dataLength,
			  		  byte[] output1, int output1Offset, int output1Length,
			  		  byte[] output2, int output2Offset, int output2Length)
	{
		int hashLength = hash.getDigestLength();
		byte[] tempKey = new byte [hashLength];
		byte[] tempHash = new byte [hashLength + 1];
		try {
			hmac(key, keyOffset, keyLength, data, dataOffset, dataLength, tempKey, 0, hashLength);
			tempHash[0] = (byte)0x01;
			hmac(tempKey, 0, hashLength, tempHash, 0, 1, tempHash, 0, hashLength);
			System.arraycopy(tempHash, 0, output1, output1Offset, output1Length);
			tempHash[hashLength] = (byte)0x02;
			hmac(tempKey, 0, hashLength, tempHash, 0, hashLength + 1, tempHash, 0, hashLength);
			System.arraycopy(tempHash, 0, output2, output2Offset, output2Length);
		} finally {
			Noise.destroy(tempKey);
			Noise.destroy(tempHash);
		}
	}
}
