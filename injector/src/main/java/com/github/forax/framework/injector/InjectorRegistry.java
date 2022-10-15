package com.github.forax.framework.injector;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import static java.util.Objects.requireNonNull;

public final class InjectorRegistry {
//	private final HashMap<Class<?>, Object> instances = new HashMap<>();
	private final HashMap<Class<?>, Supplier<?>> instances = new HashMap<>(); // Supplier<?> equivalent à Supplier<? extends Object>
	
	private static List<PropertyDescriptor> beanProperties(Class<?> type) {
	      var properties = Utils.beanInfo(type).getPropertyDescriptors();
	      if(properties == null) {
	    	  throw new IllegalStateException();
	      }
	      return List.of(properties);
	  }
	
	private static Constructor<?> findConstructor(Class<?> implementClass) {
		return Arrays.stream(implementClass.getConstructors())
				.filter(init -> init.isAnnotationPresent(Inject.class))
				.reduce((initWithInject1, initWithInject2) -> { // si le filter renvoie plusieurs inits qui ont @Inject
					throw new IllegalStateException("Multiple inits annotated with @Inject for class : " + implementClass.getName());
				})
				.orElseGet(() -> Utils.defaultConstructor(implementClass)); // retourne le init par defaut si pas de @Inject
	}
	
	// package visibility for testing
	static List<PropertyDescriptor> findInjectableProperties(Class<?> cls) {
		var properties = beanProperties(cls);
		return properties.stream()
				.filter(property -> {
					var setter = property.getWriteMethod();
					return setter != null && setter.isAnnotationPresent(Inject.class);
				}).toList();
	}
  
	public <T> void registerInstance(Class<T> type, T instance) {
		requireNonNull(type);
		requireNonNull(instance);
		registerProvider(type, () -> instance);
	}
	
	public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier) {
		requireNonNull(type);
		requireNonNull(supplier);
		if(instances.putIfAbsent(type, supplier) != null) {
			throw new IllegalStateException("The type : " + type.getName() + " is already registered");
		}
	}
	
	public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass) {
		requireNonNull(type);
		requireNonNull(providerClass);
		var init = findConstructor(providerClass);
		var initParametersTypes = init.getParameterTypes();
		var properties = findInjectableProperties(providerClass);
		
		// tout ce qui est a l'exterieur de la lambda sera execute qune seule fois
		// alors que tout ce qui est a l'interieur de la lambda seront executés à chaque appel de la lambda
		
		registerProvider(type, () -> {
			var arguments = Arrays.stream(initParametersTypes)
					.map(this::lookupInstance)
					.toArray();
			var newInstance = Utils.newInstance(init, arguments);
			for(var property: properties) {
				var setter = property.getWriteMethod();
				var argument = lookupInstance(setter.getParameterTypes()[0]); // un setter a toujours un seul argument
				Utils.invokeMethod(newInstance, setter, argument); // <=> newInstance.setter(argument)
			}
			
			return type.cast(newInstance);
		});
	}
	
	public <T> void registerProviderClass(Class<T> providerClass) {
		registerProviderClass(providerClass, providerClass);
	}
	
	public <T> T lookupInstance(Class<T> type) {
		requireNonNull(type);
		var instance = instances.get(type);
		if(instance == null) {
			throw new IllegalStateException("Ne instance associated with this type");
		}
		
		return type.cast(instance.get());
	}
}
