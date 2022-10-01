package com.github.forax.framework.mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import static java.util.stream.Collectors.joining;

public final class JSONWriter {
	private static final String SEPARATOR = "\": ";
	private final HashMap<Class<?>, Function<Object, String>> funToApply = new HashMap<>();

  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }
  
  private static String getKey(Method getter, String defaultPropertyName) {
	  var annotation = getter.getAnnotation(JSONProperty.class);
      if(annotation != null) {
      	return "\"" + annotation.value() + SEPARATOR;
      }
      
      return "\"" + defaultPropertyName + SEPARATOR;
  }
  
  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
      var properties = Utils.beanInfo(type).getPropertyDescriptors();
      if(properties == null) {
    	  throw new IllegalStateException();
      }
      return List.<PropertyDescriptor>of(properties);
  }
  
  private static List<RecordComponent> recordProperties(Class<?> type) {
	  var components = type.getRecordComponents();
	  if(components == null) {
		  throw new IllegalStateException();
      }
	  return List.<RecordComponent>of(components);
  }
  
  private static String parse(Method getter, String defaultPropertyName, JSONWriter jsonWriter, Object beanORrecord) {
	  return getKey(getter, defaultPropertyName) + jsonWriter.toJSON(Utils.invokeMethod(beanORrecord, getter));
  }

  private static final ClassValue<List<Generator>> BEAN_OR_RECORD_INFO_GENERATOR = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
    	if (!type.isRecord()) {
    		return beanProperties(type).stream()
    				.filter(property -> ! property.getName().equals("class")) // pour virer la propriete class renoye par getClass
    				.<Generator>map(property -> (jsonWriter, bean) -> parse(property.getReadMethod(), property.getName(), jsonWriter, bean))
    				.toList();
    	}
    	
    	return recordProperties(type).stream()
     			.<Generator>map(component -> (jsonWriter, record) -> parse(component.getAccessor(), component.getName(), jsonWriter, record))
     			.toList();
    }
  };

  private String parseObject(Object o) {
	  var configuredParser = funToApply.get(o.getClass());
	  if(configuredParser != null) {
		  return configuredParser.apply(o);
	  }
	  
	  var generators = BEAN_OR_RECORD_INFO_GENERATOR.get(o.getClass());
	  return generators.stream().map(generator -> generator.generate(this, o)).collect(
            joining(", ", "{", "}"));
  }

  public String toJSON(Object o) {
    return switch(o) {
      case null -> "null";
      case Boolean b -> "" + b;
      case Integer i -> "" + i;
      case Double d -> "" + d;
      case String s -> "\"" + s + "\"";
      case Object obj -> parseObject(obj);
    };
  }

  public <T> void configure(Class<T> cls, Function<T, String> applyFun) {
    Objects.requireNonNull(cls);
    Objects.requireNonNull(applyFun);
    var res = funToApply.putIfAbsent(cls, applyFun.compose(o -> cls.cast(o)));
    if(res != null) {
      throw new IllegalStateException("configuration for " + cls.getName() + " already exists");
    }
  }
}
