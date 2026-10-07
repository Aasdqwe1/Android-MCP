# 快速参考

## 最小可用技能

```
my-skill/
├── skill.json
└── README.md
```

`skill.json`：

```json
{
  "name": "my-skill",
  "description": "我的技能"
}
```

## 加一个脚本工具

```json
{
  "name": "my-skill",
  "description": "我的技能",
  "scripts": [
    {
      "name": "hello",
      "description": "打招呼",
      "entry": "scripts/hello.sh",
      "interpreter": "bash"
    }
  ]
}
```

工具名会变成 `skill_my_skill_hello`（技能名与脚本名里的非 `[A-Za-z0-9_]` 字符都会替换成 `_`，
所以 `my-skill` → `my_skill`）。
