package com.cybersiren.android.noise.southernstorm.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;

import com.cybersiren.android.noise.southernstorm.crypto.Blake2bMessageDigest;
import com.cybersiren.android.noise.southernstorm.crypto.Blake2sMessageDigest;
import com.cybersiren.android.noise.southernstorm.crypto.SHA256MessageDigest;
import com.cybersiren.android.noise.southernstorm.crypto.SHA512MessageDigest;

public final class Noise {

	public static final int MAX_PACKET_LEN = 65535;

	private static SecureRandom random = new SecureRandom();

	public static void random(byte[] data)
	{
		random.nextBytes(data);
	}

	private static boolean forceFallbacks = false;

	public static void setForceFallbacks(boolean force)
	{
		forceFallbacks = force;
	}

	public static DHState createDH(String name) throws NoSuchAlgorithmException
	{
		if (name.equals("25519"))
			return new Curve25519DHState();
		if (name.equals("448"))
			return new Curve448DHState();
		if (name.equals("NewHope"))
			return new NewHopeDHState();
		throw new NoSuchAlgorithmException("Unknown Noise DH algorithm name: " + name);
	}

	public static CipherState createCipher(String name) throws NoSuchAlgorithmException
	{
		if (name.equals("AESGCM")) {
			if (forceFallbacks)
				return new AESGCMFallbackCipherState();

			try {
				return new AESGCMOnCtrCipherState();
			} catch (NoSuchAlgorithmException e1) {

				return new AESGCMFallbackCipherState();
			}
		} else if (name.equals("ChaChaPoly")) {
			return new ChaChaPolyCipherState();
		}
		throw new NoSuchAlgorithmException("Unknown Noise cipher algorithm name: " + name);
	}

	public static MessageDigest createHash(String name) throws NoSuchAlgorithmException
	{

		if (name.equals("SHA256")) {
			if (forceFallbacks)
				return new SHA256MessageDigest();
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				return new SHA256MessageDigest();
			}
		} else if (name.equals("SHA512")) {
			if (forceFallbacks)
				return new SHA512MessageDigest();
			try {
				return MessageDigest.getInstance("SHA-512");
			} catch (NoSuchAlgorithmException e) {
				return new SHA512MessageDigest();
			}
		} else if (name.equals("BLAKE2b")) {

			if (forceFallbacks)
				return new Blake2bMessageDigest();
			try {
				return MessageDigest.getInstance("BLAKE2B-512");
			} catch (NoSuchAlgorithmException e) {
				return new Blake2bMessageDigest();
			}
		} else if (name.equals("BLAKE2s")) {

			if (forceFallbacks)
				return new Blake2sMessageDigest();
			try {
				return MessageDigest.getInstance("BLAKE2S-256");
			} catch (NoSuchAlgorithmException e) {
				return new Blake2sMessageDigest();
			}
		}
		throw new NoSuchAlgorithmException("Unknown Noise hash algorithm name: " + name);
	}

	static void destroy(byte[] array)
	{
		Arrays.fill(array, (byte)0);
	}

	static byte[] copySubArray(byte[] data, int offset, int length)
	{
		byte[] copy = new byte [length];
		System.arraycopy(data, offset, copy, 0, length);
		return copy;
	}

	static void throwBadTagException() throws BadPaddingException
	{
		try {
			Class<?> c = Class.forName("javax.crypto.AEADBadTagException");
			throw (BadPaddingException)(c.newInstance());
		} catch (ClassNotFoundException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		}
		throw new BadPaddingException();
	}
}
