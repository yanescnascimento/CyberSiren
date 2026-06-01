package com.cybersiren.android.noise.southernstorm.protocol;

import android.util.Log;

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

public class HandshakeState implements Destroyable {

	private static final String TAG = "AndroidHandshake";

	private SymmetricState symmetric;
	private boolean isInitiator;
	private DHState localKeyPair;
	private DHState localEphemeral;
	private DHState localHybrid;
	private DHState remotePublicKey;
	private DHState remoteEphemeral;
	private DHState remoteHybrid;
	private DHState fixedEphemeral;
	private DHState fixedHybrid;
	private int action;
	private int requirements;
	private short[] pattern;
	private int patternIndex;
	private byte[] preSharedKey;
	private byte[] prologue;

	public static final int INITIATOR = 1;

	public static final int RESPONDER = 2;

	public static final int NO_ACTION = 0;

	public static final int WRITE_MESSAGE = 1;

	public static final int READ_MESSAGE = 2;

	public static final int FAILED = 3;

	public static final int SPLIT = 4;

	public static final int COMPLETE = 5;

	private static final int LOCAL_REQUIRED = 0x01;

	private static final int REMOTE_REQUIRED = 0x02;

	private static final int PSK_REQUIRED = 0x04;

	private static final int FALLBACK_PREMSG = 0x08;

	private static final int LOCAL_PREMSG = 0x10;

	private static final int REMOTE_PREMSG = 0x20;

	private static final int FALLBACK_POSSIBLE = 0x40;

	public HandshakeState(String protocolName, int role) throws NoSuchAlgorithmException
	{

		String[] components = protocolName.split("_");
		if (components.length != 5)
			throw new IllegalArgumentException("Protocol name must have 5 components");
		String prefix = components[0];
		String patternId = components[1];
		String dh = components[2];
		String hybrid = null;
		String cipher = components[3];
		String hash = components[4];
		if (!prefix.equals("Noise") && !prefix.equals("NoisePSK"))
			throw new IllegalArgumentException("Prefix must be Noise or NoisePSK");
		pattern = Pattern.lookup(patternId);
		if (pattern == null)
			throw new IllegalArgumentException("Handshake pattern is not recognized");
		short flags = pattern[0];
		int extraReqs = 0;
		if ((flags & Pattern.FLAG_REMOTE_REQUIRED) != 0 && patternId.length() > 1)
			extraReqs |= FALLBACK_POSSIBLE;
		if (role == RESPONDER) {

			flags = Pattern.reverseFlags(flags);
		}
		int index = dh.indexOf('+');
		if (index != -1) {

			hybrid = dh.substring(index + 1);
			dh = dh.substring(0, index);
			if ((flags & Pattern.FLAG_LOCAL_HYBRID) == 0 || (flags & Pattern.FLAG_REMOTE_HYBRID) == 0)
				throw new IllegalArgumentException("Hybrid function specified for non-hybrid pattern");
		} else {
			if ((flags & Pattern.FLAG_LOCAL_HYBRID) != 0 || (flags & Pattern.FLAG_REMOTE_HYBRID) != 0)
				throw new IllegalArgumentException("Hybrid function not specified for hybrid pattern");
		}

		if (role != INITIATOR && role != RESPONDER)
			throw new IllegalArgumentException("Role must be initiator or responder");

		symmetric = new SymmetricState(protocolName, cipher, hash);
		isInitiator = (role == INITIATOR);
		action = NO_ACTION;
		requirements = extraReqs | computeRequirements(flags, prefix, role, false);
		patternIndex = 1;

		if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0)
			localKeyPair = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_LOCAL_EPHEMERAL) != 0)
			localEphemeral = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_LOCAL_HYBRID) != 0)
			localHybrid = Noise.createDH(hybrid);
		if ((flags & Pattern.FLAG_REMOTE_STATIC) != 0)
			remotePublicKey = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_REMOTE_EPHEMERAL) != 0)
			remoteEphemeral = Noise.createDH(dh);
		if ((flags & Pattern.FLAG_REMOTE_HYBRID) != 0)
			remoteHybrid = Noise.createDH(hybrid);

		if (localKeyPair instanceof DHStateHybrid)
			throw new NoSuchAlgorithmException("Cannot use '" + localKeyPair.getDHName() + "' for static keys");
		if (localEphemeral instanceof DHStateHybrid)
			throw new NoSuchAlgorithmException("Cannot use '" + localEphemeral.getDHName() + "' for ephemeral keys");
		if (remotePublicKey instanceof DHStateHybrid)
			throw new NoSuchAlgorithmException("Cannot use '" + remotePublicKey.getDHName() + "' for static keys");
		if (remoteEphemeral instanceof DHStateHybrid)
			throw new NoSuchAlgorithmException("Cannot use '" + remoteEphemeral.getDHName() + "' for ephemeral keys");
	}

	public String getProtocolName()
	{
		return symmetric.getProtocolName();
	}

	public int getRole()
	{
		return isInitiator ? INITIATOR : RESPONDER;
	}

	public boolean needsPreSharedKey()
	{
		if (preSharedKey != null)
			return false;
		else
			return (requirements & PSK_REQUIRED) != 0;
	}

	public boolean hasPreSharedKey()
	{
		return preSharedKey != null;
	}

	public void setPreSharedKey(byte[] key, int offset, int length)
	{
		if (length != 32) {
			throw new IllegalArgumentException
				("Pre-shared keys must be 32 bytes in length");
		}
		if ((requirements & PSK_REQUIRED) == 0) {
			throw new UnsupportedOperationException
				("Pre-shared keys are not supported for this handshake");
		}
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot set pre-shared key");
		}
		if (preSharedKey != null) {
			Noise.destroy(preSharedKey);
			preSharedKey = null;
		}
		preSharedKey = Noise.copySubArray(key, offset, length);
	}

	public void setPrologue(byte[] prologue, int offset, int length)
	{
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot set prologue");
		}
		if (this.prologue != null) {
			Noise.destroy(this.prologue);
			this.prologue = null;
		}
		this.prologue = Noise.copySubArray(prologue, offset, length);
	}

	public DHState getLocalKeyPair()
	{
		return localKeyPair;
	}

	public boolean needsLocalKeyPair()
	{
		if (localKeyPair != null)
			return !localKeyPair.hasPrivateKey();
		else
			return false;
	}

	public boolean hasLocalKeyPair()
	{
		if (localKeyPair != null)
			return localKeyPair.hasPrivateKey();
		else
			return false;
	}

	public DHState getRemotePublicKey()
	{
		return remotePublicKey;
	}

	public boolean needsRemotePublicKey()
	{
		if (remotePublicKey != null)
			return !remotePublicKey.hasPublicKey();
		else
			return false;
	}

	public boolean hasRemotePublicKey()
	{
		if (remotePublicKey != null)
			return remotePublicKey.hasPublicKey();
		else
			return false;
	}

	public DHState getFixedEphemeralKey()
	{
		if (fixedEphemeral != null)
			return fixedEphemeral;
		if (localEphemeral == null)
			return null;
		try {
			fixedEphemeral = Noise.createDH(localEphemeral.getDHName());
		} catch (NoSuchAlgorithmException e) {

			fixedEphemeral = null;
		}
		return fixedEphemeral;
	}

	public DHState getFixedHybridKey()
	{
		if (fixedHybrid != null)
			return fixedHybrid;
		if (localHybrid == null)
			return null;
		try {
			fixedHybrid = Noise.createDH(localHybrid.getDHName());
		} catch (NoSuchAlgorithmException e) {

			fixedHybrid = null;
		}
		return fixedHybrid;
	}

	private static final byte[] emptyPrologue = new byte [0];

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public void start()
	{
		if (action != NO_ACTION) {
			throw new IllegalStateException
				("Handshake has already started; cannot start again");
		}
		if ((pattern[0] & Pattern.FLAG_REMOTE_EPHEM_REQ) != 0 &&
				(requirements & FALLBACK_PREMSG) == 0) {
			throw new UnsupportedOperationException
				("Cannot start a fallback pattern");
		}

		if ((requirements & LOCAL_REQUIRED) != 0) {
			if (localKeyPair == null || !localKeyPair.hasPrivateKey())
				throw new IllegalStateException("Local static key required");
		}
		if ((requirements & REMOTE_REQUIRED) != 0) {
			if (remotePublicKey == null || !remotePublicKey.hasPublicKey())
				throw new IllegalStateException("Remote static key required");
		}
		if ((requirements & PSK_REQUIRED) != 0) {
			if (preSharedKey == null)
				throw new IllegalStateException("Pre-shared key required");
		}

		Log.d(TAG, "=== ANDROID HANDSHAKE START - INITIAL STATE ===");
		Log.d(TAG, "Protocol: " + symmetric.getProtocolName());
		Log.d(TAG, "Role: " + (isInitiator ? "INITIATOR" : "RESPONDER"));
		Log.d(TAG, "Initial symmetric hash: " + bytesToHex(symmetric.getHandshakeHash()));

		Log.d(TAG, "Mixing empty prologue");
		if (prologue != null)
			symmetric.mixHash(prologue, 0, prologue.length);
		else
			symmetric.mixHash(emptyPrologue, 0, 0);
		Log.d(TAG, "Hash after empty prologue: " + bytesToHex(symmetric.getHandshakeHash()));

		if (preSharedKey != null)
			symmetric.mixPreSharedKey(preSharedKey);

		if (isInitiator) {
			Log.d(TAG, "XX pattern - no pre-message keys to mix");
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
			if ((requirements & FALLBACK_PREMSG) != 0) {
				symmetric.mixPublicKey(remoteEphemeral);
				if (remoteHybrid != null)
					symmetric.mixPublicKey(remoteHybrid);
				if (preSharedKey != null)
					symmetric.mixPublicKeyIntoCK(remoteEphemeral);
			}
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
		} else {
			Log.d(TAG, "XX pattern - no pre-message keys to mix");
			if ((requirements & REMOTE_PREMSG) != 0)
				symmetric.mixPublicKey(remotePublicKey);
			if ((requirements & FALLBACK_PREMSG) != 0) {
				symmetric.mixPublicKey(localEphemeral);
				if (localHybrid != null)
					symmetric.mixPublicKey(localHybrid);
				if (preSharedKey != null)
					symmetric.mixPublicKeyIntoCK(localEphemeral);
			}
			if ((requirements & LOCAL_PREMSG) != 0)
				symmetric.mixPublicKey(localKeyPair);
		}

		Log.d(TAG, "=== ANDROID HANDSHAKE START - FINAL STATE ===");
		Log.d(TAG, "Final symmetric hash after mixPreMessageKeys(): " + bytesToHex(symmetric.getHandshakeHash()));
		Log.d(TAG, "===========================================");

		if (isInitiator)
			action = WRITE_MESSAGE;
		else
			action = READ_MESSAGE;
	}

	public void fallback() throws NoSuchAlgorithmException
	{
		fallback("XXfallback");
	}

	public void fallback(String patternName) throws NoSuchAlgorithmException
	{

		if ((requirements & FALLBACK_POSSIBLE) == 0)
			throw new UnsupportedOperationException("Previous handshake pattern does not support fallback");

		short[] newPattern = Pattern.lookup(patternName);
		if (newPattern == null || (newPattern[0] & Pattern.FLAG_REMOTE_EPHEM_REQ) == 0)
			throw new UnsupportedOperationException("New pattern is not a fallback pattern");

		if (isInitiator) {
			if ((action != FAILED && action != READ_MESSAGE) || !localEphemeral.hasPublicKey())
				throw new IllegalStateException("Initiator cannot fall back from this state");
		} else {
			if ((action != FAILED && action != WRITE_MESSAGE) || !remoteEphemeral.hasPublicKey())
				throw new IllegalStateException("Responder cannot fall back from this state");
		}

		String[] components = symmetric.getProtocolName().split("_");
		components[1] = patternName;
		StringBuilder builder = new StringBuilder();
		builder.append(components[0]);
		for (int index = 1; index < components.length; ++index) {
			builder.append('_');
			builder.append(components[index]);
		}
		String name = builder.toString();
		SymmetricState newSymmetric = new SymmetricState(name, components[3], components[4]);
		symmetric.destroy();
		symmetric = newSymmetric;

		if (isInitiator) {
			if (remoteEphemeral != null)
				remoteEphemeral.clearKey();
			if (remoteHybrid != null)
				remoteHybrid.clearKey();
			if (remotePublicKey != null)
				remotePublicKey.clearKey();
			isInitiator = false;
		} else {
			if (localEphemeral != null)
				localEphemeral.clearKey();
			if (localHybrid != null)
				localHybrid.clearKey();
			if ((newPattern[0] & Pattern.FLAG_REMOTE_REQUIRED) == 0 && remotePublicKey != null)
				remotePublicKey.clearKey();
			isInitiator = true;
		}
		action = NO_ACTION;
		pattern = newPattern;
		patternIndex = 1;
		short flags = pattern[0];
		if (!isInitiator) {

			flags = Pattern.reverseFlags(flags);
		}
		requirements = computeRequirements(flags, components[0], isInitiator ? INITIATOR : RESPONDER, true);
	}

	public int getAction()
	{
		return action;
	}

	private void mixDH(DHState local, DHState remote)
	{
		if (local == null || remote == null)
			throw new IllegalStateException("Pattern definition error");
		int len = local.getSharedKeyLength();
		byte[] shared = new byte [len];
		try {
			local.calculate(shared, 0, remote);
			symmetric.mixKey(shared, 0, len);
		} finally {
			Noise.destroy(shared);
		}
	}

	public int writeMessage(byte[] message, int messageOffset, byte[] payload, int payloadOffset, int payloadLength) throws ShortBufferException
	{
		int messagePosn = messageOffset;
		boolean success = false;

		if (action != WRITE_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow writing messages");
		}
		if (payload == null && (payloadOffset != 0 || payloadLength != 0)) {
			throw new IllegalArgumentException("Invalid payload argument");
		}
		if (messageOffset > message.length) {
			throw new ShortBufferException();
		}

		try {

			for (;;) {
				if (patternIndex >= pattern.length) {

					action = SPLIT;
					break;
				}
				short token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {

					action = READ_MESSAGE;
					break;
				}
				int space = message.length - messagePosn;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{

						if (localEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						if (fixedEphemeral == null)
							localEphemeral.generateKeyPair();
						else
							localEphemeral.copyFrom(fixedEphemeral);
						len = localEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						localEphemeral.getPublicKey(message, messagePosn);
						symmetric.mixHash(message, messagePosn, len);

						if (preSharedKey != null)
							symmetric.mixKey(message, messagePosn, len);
						messagePosn += len;
					}
					break;

					case Pattern.S:
					{

						if (localKeyPair == null)
							throw new IllegalStateException("Pattern definition error");
						len = localKeyPair.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						localKeyPair.getPublicKey(message, messagePosn);
						messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, len);
					}
					break;

					case Pattern.EE:
					{

						mixDH(localEphemeral, remoteEphemeral);
					}
					break;

					case Pattern.ES:
					{

						if (isInitiator)
							mixDH(localEphemeral, remotePublicKey);
						else
							mixDH(localKeyPair, remoteEphemeral);
					}
					break;

					case Pattern.SE:
					{

						if (isInitiator)
							mixDH(localKeyPair, remoteEphemeral);
						else
							mixDH(localEphemeral, remotePublicKey);
					}
					break;

					case Pattern.SS:
					{

						mixDH(localKeyPair, remotePublicKey);
					}
					break;

					case Pattern.F:
					{

						if (localHybrid == null)
							throw new IllegalStateException("Pattern definition error");
						if (localHybrid instanceof DHStateHybrid) {

							DHStateHybrid hybrid = (DHStateHybrid)localHybrid;
							if (fixedHybrid == null)
								hybrid.generateKeyPair(remoteHybrid);
							else
								hybrid.copyFrom(fixedHybrid, remoteHybrid);
						} else {
							if (fixedHybrid == null)
								localHybrid.generateKeyPair();
							else
								localHybrid.copyFrom(fixedHybrid);
						}
						len = localHybrid.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						localHybrid.getPublicKey(message, messagePosn);
						messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, len);
					}
					break;

					case Pattern.FF:
					{

						mixDH(localHybrid, remoteHybrid);
					}
					break;

					default:
					{

						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}

			if (payload != null)
				messagePosn += symmetric.encryptAndHash(payload, payloadOffset, message, messagePosn, payloadLength);
			else
				messagePosn += symmetric.encryptAndHash(message, messagePosn, message, messagePosn, 0);
			success = true;
		} finally {

			if (!success) {
				Arrays.fill(message, messageOffset, message.length - messageOffset, (byte)0);
				action = FAILED;
			}
		}
		return messagePosn - messageOffset;
	}

	public int readMessage(byte[] message, int messageOffset, int messageLength, byte[] payload, int payloadOffset) throws ShortBufferException, BadPaddingException
	{
		boolean success = false;
		int messageEnd = messageOffset + messageLength;

		if (action != READ_MESSAGE) {
			throw new IllegalStateException
				("Handshake state does not allow reading messages");
		}
		if (messageOffset > message.length || payloadOffset > payload.length) {
			throw new ShortBufferException();
		}
		if (messageLength > (message.length - messageOffset)) {
			throw new ShortBufferException();
		}

		try {

			for (;;) {
				if (patternIndex >= pattern.length) {

					action = SPLIT;
					break;
				}
				short token = pattern[patternIndex++];
				if (token == Pattern.FLIP_DIR) {

					action = WRITE_MESSAGE;
					break;
				}
				int space = messageEnd - messageOffset;
				int len, macLen;
				switch (token) {
					case Pattern.E:
					{

						if (remoteEphemeral == null)
							throw new IllegalStateException("Pattern definition error");
						len = remoteEphemeral.getPublicKeyLength();
						if (space < len)
							throw new ShortBufferException();
						symmetric.mixHash(message, messageOffset, len);
						remoteEphemeral.setPublicKey(message, messageOffset);
						if (remoteEphemeral.isNullPublicKey()) {

							throw new BadPaddingException("Null remote public key");
						}

						if (preSharedKey != null)
							symmetric.mixKey(message, messageOffset, len);
						messageOffset += len;
					}
					break;

					case Pattern.S:
					{

						if (remotePublicKey == null)
							throw new IllegalStateException("Pattern definition error");
						len = remotePublicKey.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						byte[] temp = new byte [len];
						try {
							if (symmetric.decryptAndHash(message, messageOffset, temp, 0, len + macLen) != len)
								throw new ShortBufferException();
							remotePublicKey.setPublicKey(temp, 0);
						} finally {
							Noise.destroy(temp);
						}
						messageOffset += len + macLen;
					}
					break;

					case Pattern.EE:
					{

						mixDH(localEphemeral, remoteEphemeral);
					}
					break;

					case Pattern.ES:
					{

						if (isInitiator)
							mixDH(localEphemeral, remotePublicKey);
						else
							mixDH(localKeyPair, remoteEphemeral);
					}
					break;

					case Pattern.SE:
					{

						if (isInitiator)
							mixDH(localKeyPair, remoteEphemeral);
						else
							mixDH(localEphemeral, remotePublicKey);
					}
					break;

					case Pattern.SS:
					{

						mixDH(localKeyPair, remotePublicKey);
					}
					break;

					case Pattern.F:
					{

						if (remoteHybrid == null)
							throw new IllegalStateException("Pattern definition error");
						if (remoteHybrid instanceof DHStateHybrid) {

							((DHStateHybrid)remoteHybrid).specifyPeer(localHybrid);
						}
						len = remoteHybrid.getPublicKeyLength();
						macLen = symmetric.getMACLength();
						if (space < (len + macLen))
							throw new ShortBufferException();
						byte[] temp = new byte [len];
						try {
							if (symmetric.decryptAndHash(message, messageOffset, temp, 0, len + macLen) != len)
								throw new ShortBufferException();
							remoteHybrid.setPublicKey(temp, 0);
						} finally {
							Noise.destroy(temp);
						}
						messageOffset += len + macLen;
					}
					break;

					case Pattern.FF:
					{

						mixDH(localHybrid, remoteHybrid);
					}
					break;

					default:
					{

						throw new IllegalStateException("Unknown handshake token " + Integer.toString(token));
					}
				}
			}

			int payloadLength = symmetric.decryptAndHash(message, messageOffset, payload, payloadOffset, messageEnd - messageOffset);
			success = true;
			return payloadLength;
		} finally {

			if (!success) {
				Arrays.fill(payload, payloadOffset, payload.length - payloadOffset, (byte)0);
				action = FAILED;
			}
		}
	}

	public CipherStatePair split()
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split();
		if (!isInitiator)
			pair.swap();
		action = COMPLETE;
		return pair;
	}

	public CipherStatePair split(byte[] secondaryKey, int offset, int length)
	{
		if (action != SPLIT) {
			throw new IllegalStateException
				("Handshake has not finished");
		}
		CipherStatePair pair = symmetric.split(secondaryKey, offset, length);
		if (!isInitiator) {

			pair.swap();
		}
		action = COMPLETE;
		return pair;
	}

	public byte[] getHandshakeHash()
	{
		if (action != SPLIT && action != COMPLETE) {
			throw new IllegalStateException
				("Handshake has not completed");
		}
		return symmetric.getHandshakeHash();
	}

	@Override
	public void destroy() {
		if (symmetric != null)
			symmetric.destroy();
		if (localKeyPair != null)
			localKeyPair.destroy();
		if (localEphemeral != null)
			localEphemeral.destroy();
		if (localHybrid != null)
			localHybrid.destroy();
		if (remotePublicKey != null)
			remotePublicKey.destroy();
		if (remoteEphemeral != null)
			remoteEphemeral.destroy();
		if (remoteHybrid != null)
			remoteHybrid.destroy();
		if (fixedEphemeral != null)
			fixedEphemeral.destroy();
		if (fixedHybrid != null)
			fixedHybrid.destroy();
		if (preSharedKey != null)
			Noise.destroy(preSharedKey);
		if (prologue != null)
			Noise.destroy(prologue);
	}

	private static int computeRequirements(short flags, String prefix, int role, boolean isFallback)
	{
		int requirements = 0;
	    if ((flags & Pattern.FLAG_LOCAL_STATIC) != 0) {
	        requirements |= LOCAL_REQUIRED;
	    }
	    if ((flags & Pattern.FLAG_LOCAL_REQUIRED) != 0) {
	        requirements |= LOCAL_REQUIRED;
	        requirements |= LOCAL_PREMSG;
	    }
	    if ((flags & Pattern.FLAG_REMOTE_REQUIRED) != 0) {
	        requirements |= REMOTE_REQUIRED;
	        requirements |= REMOTE_PREMSG;
	    }
	    if ((flags & (Pattern.FLAG_REMOTE_EPHEM_REQ |
	    		      Pattern.FLAG_LOCAL_EPHEM_REQ)) != 0) {
	        if (isFallback)
	            requirements |= FALLBACK_PREMSG;
	    }
	    if (prefix.equals("NoisePSK")) {
	        requirements |= PSK_REQUIRED;
	    }
	    return requirements;
	}
}
