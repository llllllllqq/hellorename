# 本项目 release 构建未开启混淆（isMinifyEnabled = false），
# 这里的规则只是备用，将来若要开启 R8 可在此补充 keep 规则。

# 保持 FileProvider
-keep class androidx.core.content.FileProvider { *; }
