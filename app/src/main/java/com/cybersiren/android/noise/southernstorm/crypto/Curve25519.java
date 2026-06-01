package com.cybersiren.android.noise.southernstorm.crypto;

import java.util.Arrays;

public final class Curve25519 {

	private static final int NUM_LIMBS_255BIT = 10;
	private static final int NUM_LIMBS_510BIT = 20;
	private int[] x_1;
	private int[] x_2;
	private int[] x_3;
	private int[] z_2;
	private int[] z_3;
	private int[] A;
	private int[] B;
	private int[] C;
	private int[] D;
	private int[] E;
	private int[] AA;
	private int[] BB;
	private int[] DA;
	private int[] CB;
	private long[] t1;
	private int[] t2;

	private Curve25519()
	{

		x_1 = new int [NUM_LIMBS_255BIT];
		x_2 = new int [NUM_LIMBS_255BIT];
		x_3 = new int [NUM_LIMBS_255BIT];
		z_2 = new int [NUM_LIMBS_255BIT];
		z_3 = new int [NUM_LIMBS_255BIT];
		A = new int [NUM_LIMBS_255BIT];
		B = new int [NUM_LIMBS_255BIT];
		C = new int [NUM_LIMBS_255BIT];
		D = new int [NUM_LIMBS_255BIT];
		E = new int [NUM_LIMBS_255BIT];
		AA = new int [NUM_LIMBS_255BIT];
		BB = new int [NUM_LIMBS_255BIT];
		DA = new int [NUM_LIMBS_255BIT];
		CB = new int [NUM_LIMBS_255BIT];
		t1 = new long [NUM_LIMBS_510BIT];
		t2 = new int [NUM_LIMBS_510BIT];
	}

	private void destroy() {

		Arrays.fill(x_1, 0);
		Arrays.fill(x_2, 0);
		Arrays.fill(x_3, 0);
		Arrays.fill(z_2, 0);
		Arrays.fill(z_3, 0);
		Arrays.fill(A, 0);
		Arrays.fill(B, 0);
		Arrays.fill(C, 0);
		Arrays.fill(D, 0);
		Arrays.fill(E, 0);
		Arrays.fill(AA, 0);
		Arrays.fill(BB, 0);
		Arrays.fill(DA, 0);
		Arrays.fill(CB, 0);
		Arrays.fill(t1,  0L);
		Arrays.fill(t2,  0);
	}

	private void reduceQuick(int[] x)
	{
		int index, carry;

		carry = 19;
		for (index = 0; index < NUM_LIMBS_255BIT; ++index) {
			carry += x[index];
			t2[index] = carry & 0x03FFFFFF;
			carry >>= 26;
		}

		int mask = -((t2[NUM_LIMBS_255BIT - 1] >> 21) & 0x01);
		int nmask = ~mask;
		t2[NUM_LIMBS_255BIT - 1] &= 0x001FFFFF;
		for (index = 0; index < NUM_LIMBS_255BIT; ++index)
			x[index] = (x[index] & nmask) | (t2[index] & mask);
	}

	private void reduce(int[] result, int[] x, int size)
	{
		int index, limb, carry;

		carry = 0;
		limb = x[NUM_LIMBS_255BIT - 1] >> 21;
		x[NUM_LIMBS_255BIT - 1] &= 0x001FFFFF;
		for (index = 0; index < size; ++index) {
			limb += x[NUM_LIMBS_255BIT + index] << 5;
			carry += (limb & 0x03FFFFFF) * 19 + x[index];
			x[index] = carry & 0x03FFFFFF;
			limb >>= 26;
			carry >>= 26;
		}
		if (size < NUM_LIMBS_255BIT) {

			for (index = size; index < NUM_LIMBS_255BIT; ++index) {
				carry += x[index];
				x[index] = carry & 0x03FFFFFF;
				carry >>= 26;
			}
		}

		carry = (x[NUM_LIMBS_255BIT - 1] >> 21) * 19;
		x[NUM_LIMBS_255BIT - 1] &= 0x001FFFFF;
		for (index = 0; index < NUM_LIMBS_255BIT; ++index) {
			carry += x[index];
			result[index] = carry & 0x03FFFFFF;
			carry >>= 26;
		}

		reduceQuick(result);
	}

	private void mul(int[] result, int[] x, int[] y)
	{
		int i, j;

		long v = x[0];
		for (i = 0; i < NUM_LIMBS_255BIT; ++i) {
			t1[i] = v * y[i];
		}
		for (i = 1; i < NUM_LIMBS_255BIT; ++i) {
			v = x[i];
			for (j = 0; j < (NUM_LIMBS_255BIT - 1); ++j) {
				t1[i + j] += v * y[j];
			}
			t1[i + NUM_LIMBS_255BIT - 1] = v * y[NUM_LIMBS_255BIT - 1];
		}

		v = t1[0];
		t2[0] = ((int)v) & 0x03FFFFFF;
		for (i = 1; i < NUM_LIMBS_510BIT; ++i) {
			v = (v >> 26) + t1[i];
			t2[i] = ((int)v) & 0x03FFFFFF;
		}

		reduce(result, t2, NUM_LIMBS_255BIT);
	}

	private void square(int[] result, int[] x)
	{
		mul(result, x, x);
	}

	private void mulA24(int[] result, int[] x)
	{
		long a24 = 121665;
		long carry = 0;
		int index;
		for (index = 0; index < NUM_LIMBS_255BIT; ++index) {
			carry += a24 * x[index];
			t2[index] = ((int)carry) & 0x03FFFFFF;
			carry >>= 26;
		}
		t2[NUM_LIMBS_255BIT] = ((int)carry) & 0x03FFFFFF;
		reduce(result, t2, 1);
	}

	private void add(int[] result, int[] x, int[] y)
	{
		int index, carry;
		carry = x[0] + y[0];
		result[0] = carry & 0x03FFFFFF;
		for (index = 1; index < NUM_LIMBS_255BIT; ++index) {
			carry = (carry >> 26) + x[index] + y[index];
			result[index] = carry & 0x03FFFFFF;
		}
		reduceQuick(result);
	}

	private void sub(int[] result, int[] x, int[] y)
	{
		int index, borrow;

		borrow = 0;
		for (index = 0; index < NUM_LIMBS_255BIT; ++index) {
			borrow = x[index] - y[index] - ((borrow >> 26) & 0x01);
			result[index] = borrow & 0x03FFFFFF;
		}

		borrow = result[0] - ((-((borrow >> 26) & 0x01)) & 19);
		result[0] = borrow & 0x03FFFFFF;
		for (index = 1; index < NUM_LIMBS_255BIT; ++index) {
			borrow = result[index] - ((borrow >> 26) & 0x01);
			result[index] = borrow & 0x03FFFFFF;
		}
		result[NUM_LIMBS_255BIT - 1] &= 0x001FFFFF;
	}

	private static void cswap(int select, int[] x, int[] y)
	{
		int dummy;
		select = -select;
		for (int index = 0; index < NUM_LIMBS_255BIT; ++index) {
			dummy = select & (x[index] ^ y[index]);
			x[index] ^= dummy;
			y[index] ^= dummy;
		}
	}

	private void pow250(int[] result, int[] x)
	{
		int i, j;

		square(A, x);
		for (j = 0; j < 9; ++j)
			square(A, A);
		mul(result, A, x);
		for (i = 0; i < 23; ++i) {
			for (j = 0; j < 10; ++j)
				square(A, A);
			mul(result, result, A);
		}

		square(A, result);
		mul(result, result, A);
		for (j = 0; j < 8; ++j) {
			square(A, A);
			mul(result, result, A);
		}
	}

	private void recip(int[] result, int[] x)
	{

	    pow250(result, x);

	    square(result, result);
	    square(result, result);
	    mul(result, result, x);
	    square(result, result);
	    square(result, result);
	    mul(result, result, x);
	    square(result, result);
	    mul(result, result, x);
	}

	private void evalCurve(byte[] s)
	{
		int sposn = 31;
		int sbit = 6;
		int svalue = s[sposn] | 0x40;
		int swap = 0;
		int select;

		for (;;) {

			select = (svalue >> sbit) & 0x01;
			swap ^= select;
	        cswap(swap, x_2, x_3);
	        cswap(swap, z_2, z_3);
	        swap = select;

	        add(A, x_2, z_2);
	        square(AA, A);
	        sub(B, x_2, z_2);
	        square(BB, B);
	        sub(E, AA, BB);
	        add(C, x_3, z_3);
	        sub(D, x_3, z_3);
	        mul(DA, D, A);
	        mul(CB, C, B);
	        add(x_3, DA, CB);
	        square(x_3, x_3);
	        sub(z_3, DA, CB);
	        square(z_3, z_3);
	        mul(z_3, z_3, x_1);
	        mul(x_2, AA, BB);
	        mulA24(z_2, E);
	        add(z_2, z_2, AA);
	        mul(z_2, z_2, E);

	        if (sbit > 0) {
	        	--sbit;
	        } else if (sposn == 0) {
	        	break;
	        } else if (sposn == 1) {
	        	--sposn;
	        	svalue = s[sposn] & 0xF8;
	        	sbit = 7;
	        } else {
	        	--sposn;
	        	svalue = s[sposn];
	        	sbit = 7;
	        }
		}

	    cswap(swap, x_2, x_3);
	    cswap(swap, z_2, z_3);
	}

	public static void eval(byte[] result, int offset, byte[] privateKey, byte[] publicKey)
	{
		Curve25519 state = new Curve25519();
		try {

			Arrays.fill(state.x_1, 0);
			if (publicKey != null) {

			    for (int index = 0; index < 32; ++index) {
			    	int bit = (index * 8) % 26;
			    	int word = (index * 8) / 26;
			    	int value = publicKey[index] & 0xFF;
			    	if (bit <= (26 - 8)) {
			    		state.x_1[word] |= value << bit;
			    	} else {
			    		state.x_1[word] |= value << bit;
			    		state.x_1[word] &= 0x03FFFFFF;
			    		state.x_1[word + 1] |= value >> (26 - bit);
			    	}
			    }

				state.reduceQuick(state.x_1);
				state.reduceQuick(state.x_1);
			} else {
				state.x_1[0] = 9;
			}

			Arrays.fill(state.x_2, 0);
			state.x_2[0] = 1;
			Arrays.fill(state.z_2, 0);
			System.arraycopy(state.x_1, 0, state.x_3, 0, state.x_1.length);
			Arrays.fill(state.z_3, 0);
			state.z_3[0] = 1;

			state.evalCurve(privateKey);

		    state.recip(state.z_3, state.z_2);
		    state.mul(state.x_2, state.x_2, state.z_3);

		    for (int index = 0; index < 32; ++index) {
		    	int bit = (index * 8) % 26;
		    	int word = (index * 8) / 26;
		    	if (bit <= (26 - 8))
		    		result[offset + index] = (byte)(state.x_2[word] >> bit);
		    	else
		    		result[offset + index] = (byte)((state.x_2[word] >> bit) | (state.x_2[word + 1] << (26 - bit)));
		    }
		} finally {

			state.destroy();
		}
	}
}
