package com.cybersiren.android.noise.southernstorm.protocol;

public interface DHState extends Destroyable {

	String getDHName();

	int getPublicKeyLength();

	int getPrivateKeyLength();

	int getSharedKeyLength();

	void generateKeyPair();

	void getPublicKey(byte[] key, int offset);

	void setPublicKey(byte[] key, int offset);

	void getPrivateKey(byte[] key, int offset);

	void setPrivateKey(byte[] key, int offset);

	void setToNullPublicKey();

	void clearKey();

	boolean hasPublicKey();

	boolean hasPrivateKey();

	boolean isNullPublicKey();

	void calculate(byte[] sharedKey, int offset, DHState publicDH);

	void copyFrom(DHState other);
}
