# org.yaml:snakeyaml 的 Representer 内部引用了 java.beans（Android 上不存在）。
# 本项目只 dump/load 普通 Map，不会走到 JavaBean 属性反射分支，因此忽略缺失类即可。
# 规则来自 AGP 生成的 app/build/outputs/mapping/release/missing_rules.txt。
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
