# Android相机应用项目

这是一个使用Kotlin和CameraX开发的Android相机应用，支持视频录制、预览和管理功能。

## 项目配置

### Gradle版本配置

项目已配置为使用以下兼容版本：
- Gradle: 8.5
- Android Gradle Plugin: 8.1.3
- Java: 11 (必须使用Java 11以利用新特性和改进)

### 如何打开和构建项目

推荐使用Android Studio来打开和构建这个项目，因为它会自动处理Gradle和依赖关系的下载与配置。

#### 使用Android Studio打开项目的步骤：

1. **安装Android Studio**：确保你已安装最新版本的Android Studio
2. **打开项目**：
   - 启动Android Studio
   - 选择 "Open an Existing Project"
   - 导航到 `/Users/dalt/code/trae/camera/AndroidApp` 目录并选择它
3. **等待Gradle同步**：
   - Android Studio会自动检测项目配置
   - 它将下载Gradle 8.5和所有必要的依赖
   - 这可能需要一些时间，取决于你的网络速度
4. **解决可能的问题**：
   - 如果遇到Java版本问题，请确保在Android Studio的SDK Manager中安装了JDK 8
   - 可以在 `File > Project Structure > SDK Location` 中配置JDK路径
5. **构建项目**：
   - 点击 "Build > Make Project" 或使用快捷键 `Ctrl+F9` (Windows/Linux) 或 `Cmd+F9` (Mac)

### 手动配置（可选）

如果你需要手动配置环境，请确保：

1. **Java 8已安装**：
   - 项目已配置为使用Java 8
   - 可以使用Amazon Corretto 8或Oracle JDK 8

2. **Android SDK已安装**：
   - 确保SDK路径正确配置在local.properties文件中
   - 已安装所需的SDK平台（API 33）

## 项目功能

- 相机预览
- 视频录制和停止
- 录制视频的保存和管理
- 录制视频的播放和删除

## 技术栈

- Kotlin
- AndroidX
- CameraX
- Media3 (视频播放)

## 注意事项

- 应用需要相机和存储权限才能正常工作
- 在首次运行时，系统会提示用户授予这些权限
- 所有录制的视频将保存在设备的外部存储中

## 故障排除

如果遇到构建问题，请尝试：

1. **清理项目**：`Build > Clean Project`
2. **删除Gradle缓存**：删除 `~/.gradle/caches` 目录
3. **重新同步Gradle**：`File > Sync Project with Gradle Files`
4. **检查网络连接**：确保可以访问Maven Central和Google的存储库