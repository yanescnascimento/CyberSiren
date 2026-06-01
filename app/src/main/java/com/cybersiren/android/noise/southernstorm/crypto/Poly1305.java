package com.cybersiren.android.noise.southernstorm.crypto;

import java.util.Arrays;

import com.cybersiren.android.noise.southernstorm.protocol.Destroyable;

public final class Poly1305 implements Destroyable {

	private byte[] nonce;
	private byte[] block;
	private int[] h;
	private int[] r;
	private int[] c;
	private long[] t;
	private int posn;

	public Poly1305()
	{
		nonce = new byte [16];
		block = new byte [16];
		h = new int [5];
		r = new int [5];
		c = new int [5];
		t = new long [10];
		posn = 0;
	}

	public void reset(byte[] key, int offset)
	{
		System.arraycopy(key, offset + 16, nonce, 0, 16);
		Arrays.fill(h, 0);
		posn = 0;

		r[0] = ((key[offset] & 0xFF)) |
			   ((key[offset + 1] & 0xFF) << 8) |
			   ((key[offset + 2] & 0xFF) << 16) |
			   ((key[offset + 3] & 0x03) << 24);
		r[1] = ((key[offset + 3] & 0x0C) >> 2) |
			   ((key[offset + 4] & 0xFC) << 6) |
			   ((key[offset + 5] & 0xFF) << 14) |
			   ((key[offset + 6] & 0x0F) << 22);
		r[2] = ((key[offset + 6] & 0xF0) >> 4) |
			   ((key[offset + 7] & 0x0F) << 4) |
			   ((key[offset + 8] & 0xFC) << 12) |
			   ((key[offset + 9] & 0x3F) << 20);
		r[3] = ((key[offset + 9] & 0xC0) >> 6) |
			   ((key[offset + 10] & 0xFF) << 2) |
			   ((key[offset + 11] & 0x0F) << 10) |
			   ((key[offset + 12] & 0xFC) << 18);
		r[4] = ((key[offset + 13] & 0xFF)) |
			   ((key[offset + 14] & 0xFF) << 8) |
			   ((key[offset + 15] & 0x0F) << 16);
	}

	public void update(byte[] data, int offset, int length)
	{
		while (length > 0) {
			if (posn == 0 && length >= 16) {

				processChunk(data, offset, false);
				offset += 16;
				length -= 16;
			} else {

				int temp = 16 - posn;
				if (temp > length)
					temp = length;
				System.arraycopy(data, offset, block, posn, temp);
				offset += temp;
				length -= temp;
				posn += temp;
				if (posn >= 16) {
					processChunk(block, 0, false);
					posn = 0;
				}
			}
		}
	}

	public void pad()
	{
		if (posn != 0) {
			Arrays.fill(block, posn, 16, (byte)0);
			processChunk(block, 0, false);
			posn = 0;
		}
	}

	public void finish(byte[] token, int offset)
	{

		if (posn != 0) {
			block[posn] = (byte)1;
			Arrays.fill(block, posn + 1, 16, (byte)0);
			processChunk(block, 0, true);
		}

		int carry = (h[4] >> 26) * 5 + h[0];
		h[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1];
		h[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2];
		h[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3];
		h[3] = carry & 0x03FFFFFF;
		h[4] = (carry >> 26) + (h[4] & 0x03FFFFFF);

		carry = 5 + h[0];
		c[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1];
		c[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2];
		c[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3];
		c[3] = carry & 0x03FFFFFF;
		c[4] = (carry >> 26) + h[4];

		int mask = -((c[4] >> 26) & 0x01);
		int nmask = ~mask;
		h[0] = (h[0] & nmask) | (c[0] & mask);
		h[1] = (h[1] & nmask) | (c[1] & mask);
		h[2] = (h[2] & nmask) | (c[2] & mask);
		h[3] = (h[3] & nmask) | (c[3] & mask);
		h[4] = (h[4] & nmask) | (c[4] & mask);

		block[0] = (byte)(h[0]);
		block[1] = (byte)(h[0] >> 8);
		block[2] = (byte)(h[0] >> 16);
		block[3] = (byte)((h[0] >> 24) | (h[1] << 2));
		block[4] = (byte)(h[1] >> 6);
		block[5] = (byte)(h[1] >> 14);
		block[6] = (byte)((h[1] >> 22) | (h[2] << 4));
		block[7] = (byte)(h[2] >> 4);
		block[8] = (byte)(h[2] >> 12);
		block[9] = (byte)((h[2] >> 20) | (h[3] << 6));
		block[10] = (byte)(h[3] >> 2);
		block[11] = (byte)(h[3] >> 10);
		block[12] = (byte)(h[3] >> 18);
		block[13] = (byte)(h[4]);
		block[14] = (byte)(h[4] >> 8);
		block[15] = (byte)(h[4] >> 16);

		carry = (nonce[0] & 0xFF) + (block[0] & 0xFF);
		token[offset] = (byte)carry;
		for (int x = 1; x < 16; ++x) {
			carry = (carry >> 8) + (nonce[x] & 0xFF) + (block[x] & 0xFF);
			token[offset + x] = (byte)carry;
		}
	}

	private void processChunk(byte[] chunk, int offset, boolean finalChunk)
	{
		int x;

		c[0] = ((chunk[offset] & 0xFF)) |
			   ((chunk[offset + 1] & 0xFF) << 8) |
			   ((chunk[offset + 2] & 0xFF) << 16) |
			   ((chunk[offset + 3] & 0x03) << 24);
		c[1] = ((chunk[offset + 3] & 0xFC) >> 2) |
			   ((chunk[offset + 4] & 0xFF) << 6) |
			   ((chunk[offset + 5] & 0xFF) << 14) |
			   ((chunk[offset + 6] & 0x0F) << 22);
		c[2] = ((chunk[offset + 6] & 0xF0) >> 4) |
			   ((chunk[offset + 7] & 0xFF) << 4) |
			   ((chunk[offset + 8] & 0xFF) << 12) |
			   ((chunk[offset + 9] & 0x3F) << 20);
		c[3] = ((chunk[offset + 9] & 0xC0) >> 6) |
			   ((chunk[offset + 10] & 0xFF) << 2) |
			   ((chunk[offset + 11] & 0xFF) << 10) |
			   ((chunk[offset + 12] & 0xFF) << 18);
		c[4] = ((chunk[offset + 13] & 0xFF)) |
			   ((chunk[offset + 14] & 0xFF) << 8) |
			   ((chunk[offset + 15] & 0xFF) << 16);
		if (!finalChunk)
			c[4] |= (1 << 24);

		h[0] += c[0];
		h[1] += c[1];
		h[2] += c[2];
		h[3] += c[3];
		h[4] += c[4];

		long hv = h[0];
		t[0] = hv * r[0];
		t[1] = hv * r[1];
		t[2] = hv * r[2];
		t[3] = hv * r[3];
		t[4] = hv * r[4];
		for (x = 1; x < 5; ++x) {
			hv = h[x];
			t[x]     += hv * r[0];
			t[x + 1] += hv * r[1];
			t[x + 2] += hv * r[2];
			t[x + 3] += hv * r[3];
			t[x + 4]  = hv * r[4];
		}

		h[0] = ((int)t[0]) & 0x03FFFFFF;
		hv = t[1] + (t[0] >> 26);
		h[1] = ((int)hv) & 0x03FFFFFF;
		hv = t[2] + (hv >> 26);
		h[2] = ((int)hv) & 0x03FFFFFF;
		hv = t[3] + (hv >> 26);
		h[3] = ((int)hv) & 0x03FFFFFF;
		hv = t[4] + (hv >> 26);
		h[4] = ((int)hv) & 0x03FFFFFF;
		hv = t[5] + (hv >> 26);
		c[0] = ((int)hv) & 0x03FFFFFF;
		hv = t[6] + (hv >> 26);
		c[1] = ((int)hv) & 0x03FFFFFF;
		hv = t[7] + (hv >> 26);
		c[2] = ((int)hv) & 0x03FFFFFF;
		hv = t[8] + (hv >> 26);
		c[3] = ((int)hv) & 0x03FFFFFF;
		hv = t[9] + (hv >> 26);
		c[4] = ((int)hv);

		int carry = h[0] + c[0] * 5;
		h[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1] + c[1] * 5;
		h[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2] + c[2] * 5;
		h[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3] + c[3] * 5;
		h[3] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[4] + c[4] * 5;
		h[4] = carry;
	}

	@Override
	public void destroy() {
		Arrays.fill(nonce, (byte)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(h, (int)0);
		Arrays.fill(r, (int)0);
		Arrays.fill(c, (int)0);
		Arrays.fill(t, (long)0);
	}
}
