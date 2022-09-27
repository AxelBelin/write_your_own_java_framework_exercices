package com.github.forax.framework.mapper;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List
import java.util.Objects;
import java.util.function.Function;

import static java.util.stream.Collectors.joining;

public final class JSONWriter {
  private final HashMap<Class<?>, Function<Objects, String>> funToApply = new HashMap<>();

  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static final ClassValue<List<Generator>> BEAN_INFO_GENERATOR = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      var beanInfo = Utils.beanInfo(type);
      var properties = beanInfo.getPropertyDescriptors();
      return Arrays.stream(properties)
              .filter(property -> ! property.getName().equals("class")) // virer la propriete class renoye par getClass
              .<Generator>map(property -> {
                var key = "\"" + property.getName() + "\"";
                var getter = property.getReadMethod();
                return (jsonWriter, bean) -> key + ": " + jsonWriter.toJSON(Utils.invokeMethod(bean, getter));
              }).toList();
    }
  };

//  private static Object extractValue(PropertyDescriptor property, Object o) {
//    return Utils.invokeMethod(o, property.getReadMethod());
//  }

  private String beanInfos(Object o) {
    var generators = BEAN_INFO_GENERATOR.get(o.getClass());
    return generators.stream().map(generator -> generator.generate(this, o)).collect(
            joining(", ", "{", "}"));
//      Method setter = property.getWriteMethod();
  }

  public String toJSON(Object o) {
    return switch(o) {
      case null -> "null";
      case Boolean b -> "" + b;
      case Integer i -> "" + i;
      case Double d -> "" + d;
      case String s -> "\"" + s + "\"";
      case Object obj -> beanInfos(obj);
    };
  }

  public <T> void configure(Class<T> cls, Function<T, String> applyFun) {
    Objects.requireNonNull(cls);
    Objects.requireNonNull(applyFun);
    var res = funToApply.putIfAbsent(cls, o -> applyFun.apply(cls.cast(o)));  // TODO use compose or andThen
    if(res != null) {
      throw new IllegalStateException("configuration for " + cls.getName() + " already exists");
    }
  }
}
