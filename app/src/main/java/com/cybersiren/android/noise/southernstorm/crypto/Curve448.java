package com.cybersiren.android.noise.southernstorm.crypto;

import java.util.Arrays;

public final class Curve448 {

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
	private int[] aa;
	private int[] bb;

	private Curve448()
	{

		x_1 = new int [16];
		x_2 = new int [16];
		x_3 = new int [16];
		z_2 = new int [16];
		z_3 = new int [16];
		A = new int [16];
		B = new int [16];
		C = new int [16];
		D = new int [16];
		E = new int [16];
		AA = new int [16];
		BB = new int [16];
		DA = new int [16];
		CB = new int [16];
		aa = new int [8];
		bb = new int [8];
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
		Arrays.fill(aa, 0);
		Arrays.fill(bb, 0);
	}

	private static long widemul_32(int a, int b)
	{
		return ((long)a) * b;
	}

	private void mul(int[] c, int[] a, int[] b)
	{
	    long accum0 = 0, accum1 = 0, accum2 = 0;
	    int mask = (1<<28) - 1;

	    int i,j;
	    for (i=0; i<8; i++) {
	        aa[i] = a[i] + a[i+8];
	        bb[i] = b[i] + b[i+8];
	    }

	    for (j=0; j<8; j++) {
	        accum2 = 0;

	        for (i=0; i<=j; i++) {
	            accum2 += widemul_32(a[j-i],b[i]);
	            accum1 += widemul_32(aa[j-i],bb[i]);
	            accum0 += widemul_32(a[8+j-i], b[8+i]);
	        }

	        accum1 -= accum2;
	        accum0 += accum2;
	        accum2 = 0;

	        for (; i<8; i++) {
	            accum0 -= widemul_32(a[8+j-i], b[i]);
	            accum2 += widemul_32(aa[8+j-i], bb[i]);
	            accum1 += widemul_32(a[16+j-i], b[8+i]);
	        }

	        accum1 += accum2;
	        accum0 += accum2;

	        c[j] = ((int)(accum0)) & mask;
	        c[j+8] = ((int)(accum1)) & mask;

	        accum0 >>>= 28;
	        accum1 >>>= 28;
	    }

	    accum0 += accum1;
	    accum0 += c[8];
	    accum1 += c[0];
	    c[8] = ((int)(accum0)) & mask;
	    c[0] = ((int)(accum1)) & mask;

	    accum0 >>>= 28;
	    accum1 >>>= 28;
	    c[9] += ((int)(accum0));
	    c[1] += ((int)(accum1));
	}

	private static void mulw(int[] c, int[] a, long b)
	{
	    int bhi = (int)(b>>28), blo = ((int)b) & ((1<<28)-1);

	    long accum0, accum8;
	    int mask = (1<<28) - 1;

	    int i;

	    accum0 = widemul_32(blo, a[0]);
	    accum8 = widemul_32(blo, a[8]);
	    accum0 += widemul_32(bhi, a[15]);
	    accum8 += widemul_32(bhi, a[15] + a[7]);

	    c[0] = ((int)accum0) & mask; accum0 >>>= 28;
	    c[8] = ((int)accum8) & mask; accum8 >>>= 28;

	    for (i=1; i<8; i++) {
	        accum0 += widemul_32(blo, a[i]);
	        accum8 += widemul_32(blo, a[i+8]);

	        accum0 += widemul_32(bhi, a[i-1]);
	        accum8 += widemul_32(bhi, a[i+7]);

	        c[i] = ((int)accum0) & mask; accum0 >>>= 28;
	        c[i+8] = ((int)accum8) & mask; accum8 >>>= 28;
	    }

	    accum0 += accum8 + c[8];
	    c[8] = ((int)accum0) & mask;
	    c[9] += accum0 >>> 28;

	    accum8 += c[0];
	    c[0] = ((int)accum8) & mask;
	    c[1] += accum8 >>> 28;
	}

	private static void weak_reduce(int[] a)
	{
	    int mask = (1<<28) - 1;
	    int tmp = a[15] >>> 28;
	    int i;
	    a[8] += tmp;
	    for (i=15; i>0; i--) {
	        a[i] = (a[i] & mask) + (a[i-1]>>>28);
	    }
	    a[0] = (a[0] & mask) + tmp;
	}

	private static void strong_reduce(int[] a)
	{
	    int mask = (1<<28) - 1;

	    a[8] += a[15]>>>28;
	    a[0] += a[15]>>>28;
	    a[15] &= mask;

	    long scarry = 0;
	    int i;
	    for (i=0; i<16; i++) {
	        scarry = scarry + (a[i] & 0xFFFFFFFFL) - ((i==8)?mask-1:mask);
	        a[i] = (int)(scarry & mask);
	        scarry >>= 28;
	    }

	     int scarry_mask = (int)(scarry & mask);
	     long carry = 0;

	     for (i=0; i<16; i++) {
	         carry = carry + (a[i] & 0xFFFFFFFFL) + ((i==8)?(scarry_mask&~1):scarry_mask);
	         a[i] = (int)(carry & mask);
	         carry >>>= 28;
	     }
	}

	private static void add(int[] out, int[] a, int[] b)
	{
		for (int i = 0; i < 16; ++i)
			out[i] = a[i] + b[i];
		weak_reduce(out);
	}

	private static void sub(int[] out, int[] a, int[] b)
	{
		int i;

		for (i = 0; i < 16; ++i)
			out[i] = a[i] - b[i];

		int co1 = ((1 << 28) - 1) * 2;
		int co2 = co1 - 2;
		for (i = 0; i < 16; ++i) {
			if (i != 8)
				out[i] += co1;
			else
				out[i] += co2;
		}

		weak_reduce(out);
	}

	private static void serialize(byte[] serial, int offset, int[] x)
	{
	    int i,j;
	    for (i=0; i<8; i++) {
	        long limb = x[2*i] + (((long)x[2*i+1])<<28);
	        for (j=0; j<7; j++) {
	            serial[offset+7*i+j] = (byte)limb;
	            limb >>= 8;
	        }
	    }
	}

	private static int is_zero(int x)
	{
	    long xx = x & 0xFFFFFFFFL;
	    xx--;
	    return (int)(xx >> 32);
	}

	private static int deserialize(int[] x, byte[] serial, int offset)
	{
	    int i,j;
	    for (i=0; i<8; i++) {
	        long out = 0;
	        for (j=0; j<7; j++) {
	            out |= (serial[offset+7*i+j] & 0xFFL)<<(8*j);
	        }
	        x[2*i] = ((int)out) & ((1<<28)-1);
	        x[2*i+1] = (int)(out >>> 28);
	    }

	    int ge = -1, mask = (1<<28)-1;
	    for (i=0; i<8; i++) {
	        ge &= x[i];
	    }

	    ge = (ge & (x[8] + 1)) | is_zero(x[8] ^ mask);

	    for (i=9; i<16; i++) {
	        ge &= x[i];
	    }

	    return ~is_zero(ge ^ mask);
	}

	private void square(int[] result, int[] x)
	{
		mul(result, x, x);
	}

	private static void cswap(int select, int[] x, int[] y)
	{
		int dummy;
		select = -select;
		for (int index = 0; index < 16; ++index) {
			dummy = select & (x[index] ^ y[index]);
			x[index] ^= dummy;
			y[index] ^= dummy;
		}
	}

	private void recip(int[] result, int[] z_2)
	{
		int posn;

	    square(B, z_2);
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(B, A);
	    mul(C, B, z_2);
	    square(B, C);
	    mul(C, B, z_2);
	    square(B, C);
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(E, A);
	    square(B, E);
	    for (posn = 1; posn < 4; ++posn) {
	        square(E, B);
	        square(B, E);
	    }
	    mul(E, B, A);
	    square(AA, E);
	    square(B, AA);
	    for (posn = 1; posn < 8; ++posn) {
	        square(AA, B);
	        square(B, AA);
	    }
	    mul(AA, B, E);
	    square(BB, AA);
	    square(B, BB);
	    for (posn = 1; posn < 16; ++posn) {
	        square(BB, B);
	        square(B, BB);
	    }
	    mul(BB, B, AA);
	    square(DA, BB);
	    square(B, DA);
	    for (posn = 1; posn < 32; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, BB);
	    square(CB, DA);
	    square(B, CB);
	    for (posn = 1; posn < 32; ++posn) {
	        square(CB, B);
	        square(B, CB);
	    }
	    mul(CB, B, BB);
	    square(DA, CB);
	    square(B, DA);
	    for (posn = 1; posn < 8; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, E);
	    square(CB, DA);
	    square(B, CB);
	    for (posn = 1; posn < 4; ++posn) {
	        square(CB, B);
	        square(B, CB);
	    }
	    mul(CB, B, A);
	    square(DA, CB);
	    square(B, DA);
	    for (posn = 1; posn < 3; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, C);
	    square(CB, DA);
	    mul(B, CB, z_2);
	    square(CB, B);
	    square(BB, CB);
	    square(B, BB);
	    for (posn = 1; posn < 111; ++posn) {
	        square(BB, B);
	        square(B, BB);
	    }
	    mul(BB, B, DA);
	    square(B, BB);
	    square(BB, B);
	    mul(result, BB, z_2);
	}

	private void evalCurve(byte[] s)
	{
		int sposn = 55;
		int sbit = 7;
		int svalue = s[sposn] | 0x80;
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
	        add(z_2, DA, CB);
	        square(x_3, z_2);
	        sub(z_2, DA, CB);
	        square(x_2, z_2);
	        mul(z_3, x_1, x_2);
	        mul(x_2, AA, BB);
	        mulw(z_2, E, 39081);
	        add(A, AA, z_2);
	        mul(z_2, E, A);

	        if (sbit > 0) {
	        	--sbit;
	        } else if (sposn == 0) {
	        	break;
	        } else if (sposn == 1) {
	        	--sposn;
	        	svalue = s[sposn] & 0xFC;
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

	public static boolean eval(byte[] result, int offset, byte[] privateKey, byte[] publicKey)
	{
		Curve448 state = new Curve448();
		int success = -1;
		try {

			Arrays.fill(state.x_1, 0);
			if (publicKey != null) {

			    success = deserialize(state.x_1, publicKey, 0);
			} else {
				state.x_1[0] = 5;
			}

			Arrays.fill(state.x_2, 0);
			state.x_2[0] = 1;
			Arrays.fill(state.z_2, 0);
			System.arraycopy(state.x_1, 0, state.x_3, 0, state.x_1.length);
			Arrays.fill(state.z_3, 0);
			state.z_3[0] = 1;

			state.evalCurve(privateKey);

		    state.recip(state.z_3, state.z_2);
		    state.mul(state.x_1, state.x_2, state.z_3);

		    strong_reduce(state.x_1);
		    serialize(result, offset, state.x_1);
		} finally {

			state.destroy();
		}
		return (success & 0x01) != 0;
	}
}
