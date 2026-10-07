package com.mcp.litert
    
    /**
     * LiteRT-LM 的硬件后端选择。
     *
     * 与 [com.mcp.core.llm.BackendType]（协议层后端）不同，这里是**同一后端内的算力档位**：
     *  - [CPU]：兼容性最好，速度最慢；
     *  - [GPU]：OpenCL，主流中高端机可用，需 manifest 声明 native library；
     *  - [NPU]：高通 HTP / 联发科 APU，最快但依赖厂商库，需 nativeLibraryDir。
     */
    enum class LiteRtBackend {
        CPU,
        GPU,
        NPU,
    }
    