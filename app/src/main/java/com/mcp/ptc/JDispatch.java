package com.mcp.ptc;

import com.mcp.toolbox.ToolCallException;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Rhino 宿主函数（P1-D）：向 JS 暴露 `_dispatch(name, argsJson)`，但**不向作用域放入任何 Java 对象**。
 *
 * 背景：旧实现把 JNative（普通 Java 类）塞进 scope，Rhino 会把 Java 成员一并暴露，
 * JS 可经 `_native.getClass()` 等反射面触达宿主类（配合 deny-all ClassShutter 前等于没有沙箱）。
 * BaseFunction 是 Rhino 的 Scriptable，JS 侧看不到 Java 成员，因此可以安全地启用 ClassShutter 全拒。
 *
 * 错误契约：宿主抛 ToolCallException 时返回哨兵串 `__PTC_ERR__{...}`，由 JS 包装层转成
 * 真正的 JS `ToolCallError`（name/toolName/message），兑现 SDK 里“失败抛 ToolCallError”的承诺。
 */
public final class JDispatch extends BaseFunction {

    public interface Delegate { String dispatch(String name, String argsJson); }

    public static final String ERR_PREFIX = "__PTC_ERR__";

    private final Delegate delegate;

    public JDispatch(Delegate delegate) { this.delegate = delegate; }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        String name = args.length > 0 ? Context.toString(args[0]) : "";
        String json = args.length > 1 ? Context.toString(args[1]) : "{}";
        try {
            return delegate.dispatch(name, json);
        } catch (ToolCallException e) {
            return ERR_PREFIX + errJson(e.getToolName(), e.getMessage());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ERR_PREFIX + errJson(name, msg);
        }
    }

    private static String errJson(String toolName, String message) {
        return "{\"toolName\":" + quote(toolName) + ",\"message\":" + quote(message) + "}";
    }

    private static String quote(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
