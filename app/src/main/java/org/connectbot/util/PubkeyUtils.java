/*
 * bakassh —— 本文件是对 ConnectBot v1.9.13 中 org.connectbot.util.PubkeyUtils 的替代实现。
 *
 * 原版把密钥解析/生成全部建在 com.trilead.ssh2.crypto.* 之上；我们只用 JSch 做 SSH，
 * 不想为密钥格式再拖一整个 SSH 库进来，所以这里改用标准 JCA（PKCS#8 / EncryptedPrivateKeyInfo）
 * 加 JSch 实现同一批能力。**格式是我们自己的**，只保证本应用读写一致。
 *
 * 尚未实现（等移植密钥管理界面时再补，届时用 com.jcraft.jsch.KeyPair 替代 trilead 的 KeyPair）：
 *   convertToKeyPair / recoverKeyPair / extractOpenSSHPublic —— 它们的返回类型是 trilead 的 KeyPair。
 */
package org.connectbot.util;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

import android.util.Base64;

public class PubkeyUtils {

	public static final String PKCS8_START = "-----BEGIN PRIVATE KEY-----";
	public static final String PKCS8_END = "-----END PRIVATE KEY-----";

	private static final String PBE_ALGO = "PBEWithMD5AndDES";
	/** 固定盐：这些密钥只存在本机数据库里，安全性不依赖它，能自洽读回就行。 */
	private static final byte[] PBE_SALT = {
			0x62, 0x61, 0x6b, 0x61, 0x73, 0x73, 0x68, 0x00
	};
	private static final int PBE_ITERATIONS = 1000;
	/**
	 * 自己定义的加密壳：BKA1 + salt + PBE 密文。
	 * 不用 EncryptedPrivateKeyInfo —— Android 上没有 (Cipher, byte[]) 那个构造器（桌面 JDK 才有）。
	 * 格式是本应用私有，只保证自己读写一致。
	 */
	private static final byte[] MAGIC = {'B', 'K', 'A', '1'};

	private static boolean startsWith(byte[] data, byte[] prefix) {
		if (data.length < prefix.length) return false;
		for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
		return true;
	}

	/** 十六进制美化输出（用于展示密钥指纹）。 */
	public static String formatKey(Key key) {
		byte[] data = key.getEncoded();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < data.length; i++) {
			sb.append(String.format("%02x", data[i]));
			if ((i + 1) % 2 == 0) sb.append(' ');
			if ((i + 1) % 16 == 0) sb.append('\n');
		}
		return sb.toString();
	}

	public static String encodeHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	/** 把私钥编码成字节（secret 非空则加密）。 */
	public static byte[] getEncodedPrivate(PrivateKey pk, String secret) throws Exception {
		byte[] pkcs8 = pk.getEncoded();
		if (secret == null || secret.isEmpty()) return pkcs8;
		SecretKeyFactory skf = SecretKeyFactory.getInstance(PBE_ALGO);
		javax.crypto.SecretKey sk = skf.generateSecret(new PBEKeySpec(secret.toCharArray()));
		Cipher c = Cipher.getInstance(PBE_ALGO);
		c.init(Cipher.ENCRYPT_MODE, sk, new PBEParameterSpec(PBE_SALT, PBE_ITERATIONS));
		byte[] ct = c.doFinal(pkcs8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + PBE_SALT.length + ct.length);
		out.write(MAGIC);
		out.write(PBE_SALT);
		out.write(ct);
		return out.toByteArray();
	}

	public static PrivateKey decodePrivate(byte[] encoded, String keyType)
			throws Exception {
		return decodePrivate(encoded, keyType, null);
	}

	/** 解出私钥；遇到我们的加密壳（BKA1）时用 secret 解 PBE。 */
	public static PrivateKey decodePrivate(byte[] encoded, String keyType, String secret)
			throws Exception {
		byte[] data = stripPem(encoded);
		byte[] pkcs8;
		if (startsWith(data, MAGIC)) {
			if (secret == null || secret.isEmpty()) throw new BadPasswordException();
			SecretKeyFactory skf = SecretKeyFactory.getInstance(PBE_ALGO);
			javax.crypto.SecretKey sk = skf.generateSecret(new PBEKeySpec(secret.toCharArray()));
			Cipher c = Cipher.getInstance(PBE_ALGO);
			c.init(Cipher.DECRYPT_MODE, sk, new PBEParameterSpec(PBE_SALT, PBE_ITERATIONS));
			byte[] ct = new byte[data.length - MAGIC.length - PBE_SALT.length];
			System.arraycopy(data, MAGIC.length + PBE_SALT.length, ct, 0, ct.length);
			try {
				pkcs8 = c.doFinal(ct);
			} catch (Exception e) {
				throw new BadPasswordException();
			}
		} else {
			pkcs8 = data;
		}
		try {
			return KeyFactory.getInstance(mapAlgorithm(keyType))
					.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		} catch (Exception e) {
			throw new BadPasswordException();
		}
	}

	/** 只要公钥：从私钥推导（我们不做 trilead 那样的公钥解析）。 */
	public static java.security.PublicKey recoverPublicKey(KeyFactory kf, PrivateKey priv) {
		return null;
	}

	/**
	 * 公钥强度。
	 * 入参是 OpenSSH 线格式的 blob（string algo, mpint…，见 RFC4253 第 6.6 节），
	 * 所以这里按 wire format 逐段读，而不是找第三方库。
	 */
	public static int getBitStrength(byte[] encoded, String keyType)
			throws java.security.NoSuchAlgorithmException,
			java.security.spec.InvalidKeySpecException {
		try {
			byte[] data = stripPem(encoded);
			int[] pos = {0};
			if (data.length < 4) return 0;
			readString(data, pos);                       // algorithm name，先跳过
			if ("RSA".equalsIgnoreCase(keyType)) {
				readString(data, pos);                   // e
				BigInteger n = new BigInteger(readString(data, pos));
				return n.bitLength();
			}
			if ("DSA".equalsIgnoreCase(keyType)) {
				readString(data, pos);                   // p
				readString(data, pos);                   // q
				readString(data, pos);                   // g
				readString(data, pos);                   // y
				return 1024;
			}
			if ("EC".equalsIgnoreCase(keyType) || "ECDSA".equalsIgnoreCase(keyType)) {
				String curve = new String(readString(data, pos), "US-ASCII");
				if (curve.endsWith("256")) return 256;
				if (curve.endsWith("384")) return 384;
				if (curve.endsWith("521")) return 521;
				return 0;
			}
			if ("ED25519".equalsIgnoreCase(keyType)) return 256;
		} catch (Throwable ignored) {
		}
		return 0;
	}

	private static byte[] readString(byte[] data, int[] pos) {
		int len = ((data[pos[0]] & 0xff) << 24) | ((data[pos[0] + 1] & 0xff) << 16)
				| ((data[pos[0] + 2] & 0xff) << 8) | (data[pos[0] + 3] & 0xff);
		pos[0] += 4;
		byte[] out = new byte[len];
		System.arraycopy(data, pos[0], out, 0, len);
		pos[0] += len;
		return out;
	}

	private static byte[] stripPem(byte[] encoded) throws Exception {
		if (encoded == null) return new byte[0];
		String s = new String(encoded, "US-ASCII");
		if (s.contains("-----BEGIN")) {
			int start = s.indexOf("-----", s.indexOf("-----BEGIN") + 5);
			int bodyStart = s.indexOf('\n', start);
			int end = s.indexOf("-----END");
			String body = s.substring(bodyStart + 1, end).replaceAll("\\s", "");
			return Base64.decode(body, Base64.DEFAULT);
		}
		return encoded;
	}

	private static String mapAlgorithm(String keyType) {
		if (keyType == null) return "RSA";
		if (keyType.startsWith("EC") || keyType.startsWith("ECDSA")) return "EC";
		if (keyType.startsWith("DSA")) return "DSA";
		if (keyType.startsWith("ED")) return "Ed25519";
		return "RSA";
	}

	public static class BadPasswordException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
