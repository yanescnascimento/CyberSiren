package com.cybersiren.android.noise.southernstorm.protocol;

public final class CipherStatePair implements Destroyable {

	private CipherState send;
	private CipherState recv;

	public CipherStatePair(CipherState sender, CipherState receiver)
	{
		send = sender;
		recv = receiver;
	}

	public CipherState getSender() {
		return send;
	}

	public CipherState getReceiver() {
		return recv;
	}

	public void senderOnly()
	{
		if (recv != null) {
			recv.destroy();
			recv = null;
		}
	}

	public void receiverOnly()
	{
		if (send != null) {
			send.destroy();
			send = null;
		}
	}

	public void swap()
	{
		CipherState temp = send;
		send = recv;
		recv = temp;
	}

	@Override
	public void destroy() {
		senderOnly();
		receiverOnly();
	}
}
