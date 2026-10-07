/**
 * bridge_polyfill.js —— 桌面端浏览器模式下的 window.ChatBridge polyfill。
 *
 * chat_desktop.html 原本是 Android WebView 专用：所有能力都依赖宿主注入的
 * 原生 @JavascriptInterface 对象 `ChatBridge`（同步返回），后端事件经
 * `window.onChatEvent(event, data)` 推送。浏览器里没有这个原生对象，本脚本
 * 用「同步 XHR + SSE」直连同进程 WebApiServer（/api/* 与 /api/events），
 * 复刻同一套接口与事件协议，让 chat_desktop.html 无需改动即可在纯浏览器操作。
 *
 * 仅在 window.ChatBridge 不存在时生效（WebView 里原生桥已就绪，直接 return）。
 */
(function () {
  "use strict";
  if (typeof window.ChatBridge !== "undefined") return; // 原生桥已存在

  // ── 同步请求：原生 @JavascriptInterface 均为同步返回，这里用同步 XHR 对齐语义 ──
  function req(method, path, body) {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path, false); // async=false
    xhr.setRequestHeader("Content-Type", "application/json");
    try {
      xhr.send(body == null ? null : JSON.stringify(body));
    } catch (e) {
      throw new Error(e && e.message ? e.message : "网络请求失败");
    }
    if (xhr.status >= 400) {
      let msg = "HTTP " + xhr.status;
      try {
        const j = JSON.parse(xhr.responseText);
        if (j && j.error) msg = j.error;
      } catch (_) {}
      throw new Error(msg);
    }
    return xhr.responseText;
  }
  function get(path) { return req("GET", path, null); }
  function post(path, body) { return req("POST", path, body || {}); }

  // 当前会话 id：由 chat_desktop.html 的会话列表在切换/新建时写入，供聊天请求携带
  function currentSid() { return window.__DS_SID || ""; }

  // 把原生桥事件回传前端（等价 ChatBridge.emit → window.onChatEvent(event, data)）。
  function emitEvent(event, data) {
    if (typeof window.onChatEvent === "function") window.onChatEvent(event, data || {});
  }

  window.__DS_POLYFILL = true;

  window.ChatBridge = {
    // ── 同步读取 ──
    getTheme: function () { return "system"; },

    checkHealth: function () {
      try {
        const j = JSON.parse(get("/web/api/health"));
        return JSON.stringify({
          sessionReady: !!(j.sessionId && j.sessionId.length),
          hasToken: !!j.hasToken,
          backend: j.backend || ""
        });
      } catch (e) {
        return JSON.stringify({ sessionReady: false, hasToken: false });
      }
    },

    requestTokenConfig: function () {
      try {
        return get("/web/api/token_config");
      } catch (e) {
        return "{}";
      }
    },

    clearTokenFlow: function (sid) {
      try {
        const s = sid || currentSid();
        return req("DELETE", "/web/api/token_flow?sessionId=" + encodeURIComponent(s), null);
      } catch (e) {
        return JSON.stringify({ ok: false, error: e && e.message ? e.message : "clear failed" });
      }
    },

    listPresets: function () {
      try {
        return get("/web/api/presets");
      } catch (e) {
        return "[]";
      }
    },

    getPreset: function () {
      try {
        const j = JSON.parse(get("/web/api/preset/current"));
        return j.presetId || "";
      } catch (e) {
        return "";
      }
    },

    setPreset: function (presetId) {
      try {
        return req("POST", "/web/api/preset/current", { presetId: presetId || "" });
      } catch (e) {
        return JSON.stringify({ ok: false, error: e && e.message ? e.message : "set failed" });
      }
    },

    getSessionWorkspacePath: function () {
      try {
        const j = JSON.parse(get("/web/api/session/workspace_path?sessionId=" + encodeURIComponent(currentSid())));
        return j.workspacePath || "";
      } catch (e) {
        return "";
      }
    },

    listSkills: function () {
      try { return get("/web/api/skills"); } catch (e) { return "[]"; }
    },

    getCurrentSessionIdForJs: function () {
      try {
        const j = JSON.parse(get("/web/api/session/current"));
        return j.sessionId || "";
      } catch (e) { return ""; }
    },

    getMessageCount: function (sid) {
      try {
        const j = JSON.parse(
          get("/web/api/sessions/" + encodeURIComponent(sid) + "/messages?offset=0&limit=1")
        );
        return j.total || 0;
      } catch (e) { return 0; }
    },

    getMessagesPage: function (sid, offset, limit) {
      try {
        return get(
          "/web/api/sessions/" + encodeURIComponent(sid) +
          "/messages?offset=" + (offset || 0) + "&limit=" + (limit || 50)
        );
      } catch (e) {
        return JSON.stringify({ messages: [], total: 0, offset: offset || 0, limit: limit || 50, hasMore: false });
      }
    },

    getTokenFlowCount: function () { return 0; },
    getTokenFlowPage: function () {
      return JSON.stringify({ entries: [], total: 0, offset: 0, limit: 50, hasMore: false });
    },
    getTokenFlowDetail: function () { return "{}"; },

    // ── 会话与分支 ──
    setParentMessageId: function (id) { /* 续聊 parent 锚点由后端按会话维护，桌面端无需回传 */ },

    forkSession: function (mid) {
      return ""; // 分支会话属 OpenAI 兼容后端 + 原生协程编排，桌面端暂不支持
    },

    resendSystemPrompt: function () { /* 桌面端不注入系统提示词重置 */ },

    // ── 聊天副作用（后端立即返回 {ok}，事件经 SSE 推回）──
    sendMessage: function (prompt, thinking, search) {
      post("/web/api/chat/send", {
        sessionId: currentSid(), prompt: prompt || "", thinking: !!thinking, search: !!search
      });
    },

    sendMessageWithAttachments: function (prompt, thinking, search, attachmentsJson) {
      post("/web/api/chat/attachments", {
        sessionId: currentSid(), prompt: prompt || "", thinking: !!thinking, search: !!search,
        attachments: attachmentsJson || "[]"
      });
    },

    regenerate: function (childMessageId, thinking, search) {
      post("/web/api/chat/regenerate", {
        sessionId: currentSid(), childMessageId: childMessageId, thinking: !!thinking, search: !!search
      });
    },

    editMessage: function (messageId, prompt, thinking, search) {
      post("/web/api/chat/edit", {
        sessionId: currentSid(), messageId: messageId, prompt: prompt || "", thinking: !!thinking, search: !!search
      });
    },

    // 以下三个都必须带 sessionId：后端 chatRoute 按请求 body 的 sessionId 显式路由
    // （多会话并发时全局 currentSessionId 可能已被别的标签页改写），body 里没有 sid
    // 时 bridge 的 XxxFor(null) 会**静默忽略**——表现为点「停止」不停、ask_user 提交无反应、
    // 删消息不生效。
    stopGeneration: function () { post("/web/api/chat/stop", { sessionId: currentSid() }); },

    submitUserInput: function (callId, response) {
      post("/web/api/chat/tool_input", { sessionId: currentSid(), callId: callId, response: response || "" });
    },

    deleteMessage: function (messageId) {
      post("/web/api/chat/delete_message", { sessionId: currentSid(), messageId: messageId });
    },

    // ── 原生硬件能力：桌面端浏览器无麦克风/TTS/附件桥，降级返回占位 ──
    startRecording: function () {
      return JSON.stringify({ status: "error", message: "桌面端不支持录音" });
    },
    stopRecording: function () {
      return JSON.stringify({ status: "error", message: "桌面端不支持录音" });
    },
    cancelRecording: function () { return "{}"; },
    transcribeAudio: function () { return "{}"; },

    // 电脑端朗读：用浏览器 Web Speech API（网页 TTS），不依赖原生系统 TTS。
    speak: function (text) {
      try {
        if (!("speechSynthesis" in window)) {
          emitEvent("tts_error", { error: "当前浏览器不支持语音合成" });
          return JSON.stringify({ ok: false, error: "当前浏览器不支持语音合成" });
        }
        var t = String(text || "");
        if (!t.trim()) { emitEvent("tts_error", { error: "文本为空" }); return JSON.stringify({ ok: false }); }
        try { window.speechSynthesis.cancel(); } catch (_) {}
        var u = new SpeechSynthesisUtterance(t);
        u.lang = "zh-CN";
        u.onend = function () { emitEvent("tts_done", {}); };
        u.onerror = function (e) { emitEvent("tts_error", { error: (e && e.error) || "语音合成失败" }); };
        window.speechSynthesis.speak(u);
        return JSON.stringify({ ok: true });
      } catch (e) {
        emitEvent("tts_error", { error: (e && e.message) ? e.message : "语音合成失败" });
        return JSON.stringify({ ok: false });
      }
    },
    playAudio: function () { return "{}"; },
    stopAudio: function () {
      try { if ("speechSynthesis" in window) window.speechSynthesis.cancel(); } catch (_) {}
      return "{}";
    },

    // 电脑端附件选择：用 <input type="file"> 打开文件选择器，读成 dataURL + 文本内容后
    // 回传 attachments_picked（与 WebView 原生流程一致）。
    pickAttachments: function () {
      try {
        var input = document.createElement("input");
        input.type = "file";
        input.multiple = true;
        input.style.display = "none";
        document.body.appendChild(input);
        input.onchange = function () {
          var files = Array.from(input.files || []);
          try { document.body.removeChild(input); } catch (_) {}
          if (files.length === 0) return;
          var results = [];
          var pending = files.length;
          files.forEach(function (file) {
            var mime = file.type || "";
            var isImage = mime.indexOf("image/") === 0;
            var isText = mime.indexOf("text/") === 0 ||
              /\.(txt|md|markdown|json|js|mjs|ts|tsx|jsx|py|java|kt|kts|xml|yml|yaml|csv|log|html|htm|css|scss|sh|bash|sql|conf|ini|toml)$/i.test(file.name || "");
            var reader = new FileReader();
            reader.onload = function () {
              var dataUrl = reader.result || "";
              var att = { path: dataUrl, name: file.name || "附件", mime: mime, kind: isImage ? "image" : (isText ? "text" : "other"), size: file.size || 0, preview: isImage ? dataUrl : "" };
              var doneOne = function () { results.push(att); if (--pending === 0) emitEvent("attachments_picked", { attachments: results, list: results }); };
              if (isText) {
                var tr = new FileReader();
                tr.onload = function () { att.text = tr.result || ""; doneOne(); };
                tr.onerror = function () { doneOne(); };
                tr.readAsText(file);
              } else {
                doneOne();
              }
            };
            reader.onerror = function () { if (--pending === 0) emitEvent("attachments_picked", { attachments: results, list: results }); };
            reader.readAsDataURL(file);
          });
        };
        input.click();
        return "{}";
      } catch (e) {
        emitEvent("attachment_error", { message: (e && e.message) ? e.message : "打开文件选择器失败" });
        return "{}";
      }
    }
  };

  // ── SSE 事件流：把后端广播转发给 window.onChatEvent(event, data) ──
  function startEvents() {
    if (typeof window.EventSource === "undefined") return;
    try {
      const es = new EventSource("/web/api/events");
      es.onmessage = function (msg) {
        try {
          const env = JSON.parse(msg.data);
          const eventName = env.event;
          // 多会话：后端广播**所有会话**的事件（服务端不再按 currentSessionId 拦截），
          // 这里按 envelope.sid 只认当前显示的会话——否则后台会话的 content/done
          // 会被渲染进当前会话的气泡与按钮状态。sid 为 null 的全局事件照常放行。
          const envSid = env.sid == null ? "" : String(env.sid);
          if (envSid && envSid !== currentSid()) return;
          let data = env.data;
          if (typeof data === "string") {
            try { data = JSON.parse(data); } catch (_) { /* 保持字符串 */ }
          }
          if (typeof window.onChatEvent === "function") {
            window.onChatEvent(eventName, data || {});
          }
        } catch (_) { /* 非 JSON 帧忽略 */ }
      };
      window.__DS_ES = es;
    } catch (_) { /* SSE 不支持则静默 */ }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startEvents);
  } else {
    startEvents();
  }

  window.addEventListener("beforeunload", function () {
    if (window.__DS_ES) { try { window.__DS_ES.close(); } catch (_) {} }
  });
})();