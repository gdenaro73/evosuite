package com.examples.with.different.packagename.rmi.testlistener;

public class SampleTargetProgram {

	public SampleTargetProgram(boolean valid) {
		super();
		try {
			if (!valid) {
				throw new RuntimeException("contruction is invalid");
			}
		} catch (Throwable e) {
			throw e;
		}
	}

	public int sum1(int a, int b) {
		switch (a) {
		case 1: a = 1;
		case 2: a = 2;
		default: a = a + a - a;
		}
		return a + b;
	}

	/* test with 
	 * considered precond: a > 10000
	 * considered postconds: 
	 * 	 ret < 0
	 * 	 ret > 0
	 *   throw RuntimeExceptions
	 */
	public int sum(int a, int b) {
		try {
			try {
				check(a, b);
			} catch (NullPointerException e) {
				//mask exception
			}
			if (a < - 10) { 
				throw new RuntimeException("check failed"); /* This exc is incompatible with the considered precondition */
			} else if (a < 0) {
				return -a + b;
			} else if (a + b == 1578 + 1234) {
				return a + b;
			} else if (a + b == 10001) {
				return a + b;
			} else {
				return a + 2 * b - b;
			}
		} catch (Throwable e) {
			throw e;
		}
	}

	public int other(int a, int b) {
		check(a, b);
		if (a < 0) {
			return -a + b;
		} else {
			return a + b;
		}
	}

	private void check(int a, int b) {
		if (a + b < -10000 || a + b > 10000) { 
			throw new RuntimeException("check failed"); /* ...but this exc is compatible */
		}		
	}
}
