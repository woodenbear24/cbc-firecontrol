# CBC Fire Control - NeoForge Mod

一个基于 **Create Big Cannons** 的火控扩展 Mod。

## 环境信息

| 组件 | 版本 |
|------|------|
| Minecraft | **1.21.1** |
| NeoForge | **21.1.265** |
| Java | **21** (JDK 21) |
| Gradle | 9.2.1 (via wrapper) |

## 依赖 Mod

| Mod | 版本 | Mod ID |
|-----|------|--------|
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | 6.0.10-280 | `create` |
| [Create Big Cannons](https://modrinth.com/mod/create-big-cannons) | 5.11.7 | `createbigcannons` |
| [Ritchie's Projectile Library](https://modrinth.com/mod/rpl) | 2.1.2 | `ritchiesprojectilelib` |

## 前置要求

- **JDK 21** (64-bit) - 推荐 [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download)
- **IntelliJ IDEA** 或 **Eclipse**（官方支持）

## 快速开始

1. 用 IDE 打开此 Gradle 项目
2. 等待 Gradle 完成同步（首次运行可能需要 30-60 分钟下载并反编译 Minecraft）
3. 修改 `gradle.properties` 自定义你的 mod 信息

## 常用命令

```powershell
.\gradlew.bat build        # 构建 mod JAR（输出到 build/libs/）
.\gradlew.bat runClient    # 启动开发客户端（带 Create + CBC）
.\gradlew.bat runServer    # 启动专用服务器
.\gradlew.bat runData      # 运行数据生成器
```

## 项目结构

```
├── build.gradle                         # 构建脚本（含依赖声明）
├── gradle.properties                    # Mod 元数据 & 依赖版本
├── settings.gradle                      # Gradle 设置
├── gradlew / gradlew.bat                # Gradle Wrapper
└── src/
    ├── main/
    │   ├── java/com/cbc/firecontrol/    # Mod 源代码
    │   │   └── CBCFireControl.java      # 主入口（@Mod）
    │   ├── resources/assets/cbc_firecontrol/  # Mod 资源
    │   │   └── lang/en_us.json          # 语言文件
    │   └── templates/META-INF/
    │       └── neoforge.mods.toml       # Mod 元数据模板
    └── generated/resources/             # Datagen 输出（自动添加）
```

## 关键 Gradle 配置说明

### Maven 仓库
- `maven.createmod.net` — Create, Ponder, Flywheel
- `maven.ithundxr.dev/snapshots` — Registrate
- `api.modrinth.com/maven` — CBC, RPL（通过 Modrinth 项目 ID）

### 依赖方式
- Create + 组件库：`implementation`（直接 maven 坐标）
- CBC + RPL：`compileOnly` + `localRuntime`（编译时可用，运行时仅本地测试用，不发布为硬依赖）

### neoforge.mods.toml 中声明的运行时依赖
- `create` (required)
- `createbigcannons` (required)
- `ritchiesprojectilelib` (required)

## 开发指南

### 访问 Create 内部 API
Create 类在编译时可用（通过 `implementation` 依赖），你可以直接 import：
```java
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
```

### 访问 CBC 内部 API
CBC 类在编译时可用（通过 `compileOnly`），你可以 import：
```java
import com.rbasamoyai.createbigcannons.cannon_control.conctription.ContributionData;
import com.rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
```

### 注册方块/物品
使用 `DeferredRegister`（见 `CBCFireControl.java` 示例）。

## 资源

- [NeoForge 文档](https://docs.neoforged.net/)
- [Create Wiki](https://wiki.createmod.net/)
- [CBC GitHub](https://github.com/Cannoneers-of-Create/CreateBigCannons/tree/create-v6-1.21.1)
- [NeoForge Discord](https://discord.neoforged.net/)
