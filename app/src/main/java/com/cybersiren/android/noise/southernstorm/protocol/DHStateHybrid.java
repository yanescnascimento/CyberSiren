package com.cybersiren.android.noise.southernstorm.protocol;

public interface DHStateHybrid extends DHState {

	void generateKeyPair(DHState remote);

	void copyFrom(DHState other, DHState remote);

	void specifyPeer(DHState local);
}
