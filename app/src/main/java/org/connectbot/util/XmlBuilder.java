/*
 * bakassh —— 本文件是对 ConnectBot 同名类的替代实现。
 * 原版依赖 com.trilead.ssh2.crypto.Base64；这里改用 android.util.Base64，
 * 只为给从 ConnectBot 拷来的 bean 提供 toXML()（备份导出用）。
 */
package org.connectbot.util;

import android.util.Base64;

import java.util.Map;

public class XmlBuilder {

	private final StringBuilder sb = new StringBuilder(512);

	public XmlBuilder() {
		sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
	}

	public XmlBuilder append(String data) {
		sb.append(data);
		return this;
	}

	public XmlBuilder append(String field, Object data) {
		sb.append('<').append(field).append('>');
		if (data instanceof byte[]) {
			sb.append(Base64.encodeToString((byte[]) data, Base64.NO_WRAP));
		} else if (data instanceof Map) {
			// ConnectBot 的 bean 值里有 Map（端口转发参数），按 <k>v</k> 展开
			@SuppressWarnings("unchecked")
			Map<String, String> map = (Map<String, String>) data;
			for (Map.Entry<String, String> e : map.entrySet()) {
				sb.append('<').append(e.getKey()).append('>');
				sb.append(e.getValue());
				sb.append("</").append(e.getKey()).append('>');
			}
		} else {
			sb.append(String.valueOf(data));
		}
		sb.append("</").append(field).append(">\n");
		return this;
	}

	@Override
	public String toString() {
		return sb.toString();
	}
}
