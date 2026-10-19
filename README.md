# MyThon

**MyThon** 是一个专为 Minecraft 26.3 (Fabric) 打造的高级、轻量级 Python 脚本执行引擎。它的核心目标是打破传统模组生态中“为了集成脚本引擎而必须打包庞大运行时”的桎梏，通过创新的“按需分配、动态拉取”机制，为服主和开发者提供一个极致轻量、无缝衔接的自动化脚本运行环境。

## 目录

1. 设计背景与核心理念
2. 核心功能特性
3. 架构设计与工作原理
4. 快速开始与指令说明
5. 脚本编写指南与示例
6. 开发者构建指南
7. 常见问题解答 (FAQ)
8. 开源协议与致谢

## 1. 设计背景与核心理念

在传统的 Minecraft 模组开发中，如果想要在服务端运行 Python 脚本，通常需要将完整的 Python 运行环境（包括解释器、标准库、动态链接库等）硬编码打包进模组的 .jar 文件中。这种做法会导致：

- **模组体积臃肿**：一个简单的 JAR 包动辄上百兆，极大地拖慢了服务器启动速度，且增加了分发的网络负担。
- **跨平台兼容性灾难**：同一个 JAR 包如果在不同架构（如 x86_64 的 Linux 和 ARM64 的服务器）之间流转，内置的解释器根本无法运行。
- **维护成本极高**：当 Python 发布新版本或出现安全漏洞时，开发者必须重新打包整个 JAR 并发布更新。

MyThon 彻底改变了这一现状。我们相信：运行环境不应该成为代码的累赘。

MyThon 的 JAR 包体积被压缩到了极致（仅包含几百 KB 的 Java 代码和配置文件）。当你需要在服务器上运行 Python 脚本时，模组会自动联网，从官方或可信的镜像源拉取纯净的、免安装的 Python 独立构建版本，并按需解压到服务端的配置目录中。

这种设计不仅让模组的分发变得极其轻量，还赋予了用户极大的自由度。你可以随时切换 Python 的版本，或者直接调用操作系统自带的 Python 解释器，完全摆脱了模组作者打包的限制。

## 2. 核心功能特性

- **极致轻量的宿主程序**：MyThon 自身的构建产物微乎其微，不包含任何二进制运行库，完美融入你的 mods 文件夹，不会对服务器的启动时间造成任何影响。
- **跨平台动态分发机制**：通过内置的下载器，支持自动获取适用于 Windows x86_64、Linux x86_64 等不同平台的独立 Python 构建包，真正做到“一次编写，到处运行”。
- **原生系统级降级策略**：对于 Linux 服务器，如果目标系统已经自带了高质量的 Python 环境（例如通过 apt、yum 或 apk 安装），MyThon 可以直接调用系统级解释器，省去下载和解压的任何开销。
- **实时流式输出拦截**：打破了子进程输出的黑盒。Python 脚本内的 print 打印内容，会通过 Netty 管道实时重定向回 Minecraft 的服务器控制台或游戏内的聊天栏，方便服主随时监控脚本运行状态。
- **完善的异步调度与生命周期管理**：支持脚本的单次执行和按指定间隔时间的循环执行。循环任务运行在独立的守护线程中，不会阻塞 Minecraft 的主游戏线程（Tick Loop），确保服务器 TPS 稳定。
- **多任务热插拔与终止**：对于正在循环执行的脚本，再次输入相同指令即可自动阻断并安全中断对应的线程，防止内存泄漏。

## 3. 架构设计与工作原理

MyThon 的底层架构分为三个核心模块：

### 3.1 命令拦截与解析模块 (Brigadier)

当玩家或控制台输入以 /python 开头的命令时，MyThon 会通过 Fabric API 提供的 CommandRegistrationCallback 拦截并解析这些指令。通过 Brigadier 框架，我们实现了复杂的参数校验、Tab 键补全建议（Suggestions）以及子命令的分发。

### 3.2 运行时环境管理器 (Runtime Manager)

这是 MyThon 的核心所在。它维护着一个平台映射表，记录了各个操作系统的下载地址和解压后的路径规则。
- 当用户输入 /python set <平台名> 时，运行时管理器会启动一个独立的后台线程。
- 后台线程会通过 HttpURLConnection 向目标地址发起请求，将压缩包下载到本地临时目录。
- 如果是 .zip 文件，Java 自带的 ZipInputStream 会负责解压；如果是 .tar.gz 文件，MyThon 会巧妙地调用操作系统的 tar 命令来完成解压，避免了引入庞大的第三方压缩库。

### 3.3 进程调度与流重定向 (Process Controller)

当用户执行脚本时，MyThon 会使用 ProcessBuilder 启动一个子进程。
- 为了确保 Python 的输出能实时显示，我们在 ProcessBuilder 中追加了 -u 参数，强制 Python 不使用输出缓冲区。
- 子进程的 Standard Output (stdout) 和 Standard Error (stderr) 会被合并，然后通过 BufferedReader 逐行读取。
- 读取到的每一行字符串，都会被重新包装成 Minecraft 的 Component，并通过 CommandSourceStack 发送给执行命令的玩家或控制台。
- 整个脚本执行过程运行在异步线程中，绝对不占用服务器的 Tick 时间。

## 4. 快速开始与指令说明

在服务器成功加载 MyThon 模组后，服务器根目录下会自动生成 config/mython/python/ 目录。你可以将你的 .py 脚本放置于此。

以下为命令列表：

### 4.1 环境配置
- /python set 
  列出所有内置支持的 Python 平台列表，并显示当前各环境的下载与解压状态。

- /python set <平台名> 
  触发下载任务。系统会在后台线程从官方源拉取对应平台的免安装 Python 压缩包，自动解压到 config/mython/python/<平台名>/ 下，并设为当前激活环境。
  可用平台名：
  - linux-system：直接复用系统自带的 Python 3。
  - linux-x86_64：下载并配置 Linux 64 位独立 Python 环境。
  - windows-x86_64：下载并配置 Windows 64 位独立 Python 环境。

### 4.2 脚本执行
- /python py <文件名.py>
  执行一次指定的 Python 脚本。

- /python py <文件名.py> c
  循环执行指定的脚本，默认间隔为 5 秒。

- /python py <文件名.py> c <秒数>
  按指定的时间间隔（单位：秒）循环执行脚本。
  注意：循环执行时，如果再次输入相同的启动命令，会立即停止当前脚本的循环任务。

### 4.3 其他
- /python version
  输出当前 MyThon 模组的版本号。

- /python help
  显示所有可用指令的帮助菜单。

## 5. 脚本编写指南与示例

MyThon 支持标准的 Python 3 语法。由于输出流被重定向到了游戏内，你可以直接在脚本中使用 print 函数来向玩家发送消息。

示例 1：最基础的 Hello World
在 config/mython/python/ 目录下新建 hello.py，输入以下内容：
print("Hello, Minecraft World! 来自 MyThon 的问候。")
然后在游戏内输入 /python py hello.py，聊天栏就会立刻显示这句话。

示例 2：带参数的自动化签到脚本
虽然 MyThon 目前不直接提供 Bukkit API 或 Fabric API 的 Python 绑定，但你可以通过写入文件的方式与其他插件进行交互（例如配合 Plan 或 RCON）。
新建 check_in.py，输入以下内容：
import time
import datetime
current_time = datetime.datetime.now()
print("当前服务器时间: " + str(current_time))
print("执行自动化签到逻辑...")

然后在游戏内输入 /python py check_in.py c 60，这个脚本就会每分钟执行一次。

## 6. 开发者构建指南

如果你希望从源码构建 MyThon，请确保你的开发环境满足以下要求：

- **操作系统**: Linux 或 macOS (Windows 下使用 WSL 效果更佳)
- **JDK**: Java 25 (Minecraft 26.3 要求的最低 Java 版本)
- **构建工具**: Gradle

进入项目根目录，执行 ./gradlew build 即可编译出模组。

编译生成的 JAR 文件将输出在 build/libs/ 目录下，将其放入服务端的 mods 文件夹即可使用。

## 7. 常见问题解答 (FAQ)

Q: 为什么点击下载后没有反应？
A: 下载任务运行在独立的异步线程中，大型运行环境可能需要几分钟的下载时间。请检查服务器控制台是否有网络报错信息。如果服务器无法直连 GitHub 或官方源，可能会导致下载失败。

Q: 脚本执行报错找不到 Python 或者报缺少动态库？
A: 请确保你在使用 /python set 指令时选择了正确的平台。如果是 Linux 环境，且下载的独立 Python 无法运行（通常是因为目标系统极度精简，缺少 glibc 或 libstdc++ 等底层库），建议直接使用 linux-system 选项，调用系统自带的 Python 环境。

Q: 我可以自己往里面加 Node.js 或者 Ruby 的支持吗？
A: 可以。MyThon 的底层逻辑高度模块化。你只需要在源码的 downloadUrls 字典中增加对应的环境下载链接，并适配解压逻辑，就可以无缝扩展 MyThon 的能力边界。

Q: 脚本会影响服务器的 TPS 吗？
A: 不会。MyThon 所有的脚本执行和循环调度都在独立的守护线程中运行，完全不干涉 Minecraft 的主线程 (Server Thread)。

## 8. 开源协议与致谢

本项目遵循开源协议发布。感谢 Fabric 团队提供的优秀模组加载框架，感谢 Python 社区提供的独立构建版本。

---
MyThon - 让 Minecraft 服务器拥有无限的可能性。
