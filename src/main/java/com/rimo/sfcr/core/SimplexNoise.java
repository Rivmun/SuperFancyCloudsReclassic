package com.rimo.sfcr.core;

import java.util.Random;

// 3d noise sampler impl powered by deepseek.ai
public class SimplexNoise {
	private static final int[][] grad3 = {
			{1, 1, 0}, {- 1, 1, 0}, {1, - 1, 0}, {- 1, - 1, 0},
			{1, 0, 1}, {- 1, 0, 1}, {1, 0, - 1}, {- 1, 0, - 1},
			{0, 1, 1}, {0, - 1, 1}, {0, 1, - 1}, {0, - 1, - 1}
	};
	private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
	private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
	private static final double F3 = 1.0 / 3.0;
	private static final double G3 = 1.0 / 6.0;

	private final int[] p = new int[512]; // 双倍长度的排列数组
	private final int[] perm = new int[512];

	public SimplexNoise(long seed) {
		Random rand = new Random(seed);
		int[] permutation = new int[256];
		for (int i = 0; i < 256; i++) permutation[i] = i;
		for (int i = 0; i < 256; i++) {
			int j = rand.nextInt(256 - i) + i;
			int temp = permutation[i];
			permutation[i] = permutation[j];
			permutation[j] = temp;
		}
		for (int i = 0; i < 512; i++) {
			p[i] = permutation[i & 255];
			perm[i] = p[i];
		}
	}

	// 3D Simplex 噪声主方法
	public double noise(double x, double y, double z) {
		double n0 = 0, n1 = 0, n2 = 0, n3 = 0;

		// 噪声变换到简单立方体
		double s = (x + y + z) * F3;
		int i = fastFloor(x + s);
		int j = fastFloor(y + s);
		int k = fastFloor(z + s);
		double t = (i + j + k) * G3;
		double X0 = i - t;
		double Y0 = j - t;
		double Z0 = k - t;
		double x0 = x - X0;
		double y0 = y - Y0;
		double z0 = z - Z0;

		// 确定单纯形角点
		int i1, j1, k1;
		int i2, j2, k2;
		if (x0 >= y0) {
			if (y0 >= z0) {
				i1 = 1;
				j1 = 0;
				k1 = 0;
				i2 = 1;
				j2 = 1;
				k2 = 0;
			} else if (x0 >= z0) {
				i1 = 1;
				j1 = 0;
				k1 = 0;
				i2 = 1;
				j2 = 0;
				k2 = 1;
			} else {
				i1 = 0;
				j1 = 0;
				k1 = 1;
				i2 = 1;
				j2 = 0;
				k2 = 1;
			}
		} else {
			if (y0 < z0) {
				i1 = 0;
				j1 = 0;
				k1 = 1;
				i2 = 0;
				j2 = 1;
				k2 = 1;
			} else if (x0 < z0) {
				i1 = 0;
				j1 = 1;
				k1 = 0;
				i2 = 0;
				j2 = 1;
				k2 = 1;
			} else {
				i1 = 0;
				j1 = 1;
				k1 = 0;
				i2 = 1;
				j2 = 1;
				k2 = 0;
			}
		}

		// 角点坐标偏移
		double x1 = x0 - i1 + G3;
		double y1 = y0 - j1 + G3;
		double z1 = z0 - k1 + G3;
		double x2 = x0 - i2 + 2.0 * G3;
		double y2 = y0 - j2 + 2.0 * G3;
		double z2 = z0 - k2 + 2.0 * G3;
		double x3 = x0 - 1.0 + 3.0 * G3;
		double y3 = y0 - 1.0 + 3.0 * G3;
		double z3 = z0 - 1.0 + 3.0 * G3;

		// 计算每个角点的梯度贡献
		int ii = i & 255;
		int jj = j & 255;
		int kk = k & 255;
		int gi0 = perm[ii + perm[jj + perm[kk]]] % 12;
		int gi1 = perm[ii + i1 + perm[jj + j1 + perm[kk + k1]]] % 12;
		int gi2 = perm[ii + i2 + perm[jj + j2 + perm[kk + k2]]] % 12;
		int gi3 = perm[ii + 1 + perm[jj + 1 + perm[kk + 1]]] % 12;

		double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
		if (t0 < 0) n0 = 0.0;
		else {
			t0 *= t0;
			n0 = t0 * t0 * dot(grad3[gi0], x0, y0, z0);
		}

		double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
		if (t1 < 0) n1 = 0.0;
		else {
			t1 *= t1;
			n1 = t1 * t1 * dot(grad3[gi1], x1, y1, z1);
		}

		double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
		if (t2 < 0) n2 = 0.0;
		else {
			t2 *= t2;
			n2 = t2 * t2 * dot(grad3[gi2], x2, y2, z2);
		}

		double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
		if (t3 < 0) n3 = 0.0;
		else {
			t3 *= t3;
			n3 = t3 * t3 * dot(grad3[gi3], x3, y3, z3);
		}

		// 求和并归一化到[-1,1]区间
		return 32.0 * (n0 + n1 + n2 + n3);
	}

	private int fastFloor(double x) {
		return x > 0 ? (int) x : (int) x - 1;
	}

	private double dot(int[] g, double x, double y, double z) {
		return g[0] * x + g[1] * y + g[2] * z;
	}
}
