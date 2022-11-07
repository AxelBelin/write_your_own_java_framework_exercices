package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static java.util.Objects.requireNonNull;
import java.util.stream.Stream;

public final class InterceptorRegistry {
//  private AroundAdvice advice;
//  private final HashMap<Class<?>, List<AroundAdvice>> advicesMap = new HashMap<>();
  private final HashMap<Class<?>, List<Interceptor>> interceptorsMap = new HashMap<>();

  // Invalider le cache lorsque l'on ajoute un nouvel Interceptor associé à une annotation car lorsque l'on appelle une méthode, on doit aussi appeler ses Interceptor associés.
  // Ainsi, les appels de méthodes changent donc il faut invalider le cache pour éviter des problèmes liés aux Invocations.
  private final HashMap<Method, Invocation> cache = new HashMap<>();

  static Invocation getInvocation(List<Interceptor> interceptorList) {
    Invocation invocation = Utils::invokeMethod; // si on a pas d'nterceptor. Cette ligne est équiv a : (instance, method, args) -> Utils.invokeMethod(instance, method, args)
    var reversedList = Utils.reverseList(interceptorList);
    for(var interceptor: reversedList) {
      var oldInvocation = invocation; // on doit catcher car déclaré en dehors de la bocule
      invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, oldInvocation); // liste chainée : on fait une lambda qui appelle le prochain interceptor. Le prochain interceptor appellera a son tour l'ancienne invocation qi peut être soit la méthode, soit la prochaine invocation
    }

    return invocation;
  }

  List<Interceptor> findInterceptors(Method method) {
    //    return Arrays.stream()
//            .flatMap(annotation -> interceptorsMap.getOrDefault(annotation.annotationType(), List.of()).stream())
//            .toList();

    var classAnnotations = Arrays.stream(method.getDeclaringClass().getDeclaredAnnotations());
    var methodAnnotation = Arrays.stream(method.getDeclaredAnnotations());
    var parametersAnnotation = Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream);
    return Stream.of(classAnnotations, methodAnnotation, parametersAnnotation)
            .flatMap(s -> s)
            .distinct()
            .flatMap(annotation -> interceptorsMap.getOrDefault(annotation.annotationType(), List.of()).stream())
            .toList();
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor) { // Toutes les annotations héritent/implémentent l'interface Annotation
    requireNonNull(annotationClass);
    requireNonNull(interceptor);
    interceptorsMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>())
            .add(interceptor);
    cache.clear(); // invalider le cache ici
  }

//  List<AroundAdvice> findAdvices(Method method) {
//    // On fait un flatMap car pour une annotation on peut avoir plusioeurs advices dans la map -> c'est comme une boucle imbriquée.
//    // List.of() renvoie toujours la même instance car c'est une liste immutable -> c'est une constance donc on peut le mettre en 2ème param de getOrDefault
//    return Arrays.stream(method.getDeclaredAnnotations())
//            .flatMap(annotation -> advicesMap.getOrDefault(annotation.annotationType(), List.of()).stream())
//            .toList();
//  }

//  public void addAroundAdvice(Class<?> annotationClass, AroundAdvice aroundAdvice) {
//    requireNonNull(annotationClass);
//    requireNonNull(aroundAdvice);
//    advicesMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>())
//            .add(aroundAdvice); // __ = Osef du paramètre de la Function on veut pas lui donner un nom donc __ car c'est un nom de variable valide
//  }

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) { // Toutes les annotations héritent/implémentent l'interface Annotation
    requireNonNull(annotationClass);
    requireNonNull(aroundAdvice);
    addInterceptor(annotationClass, (instance, method, args, invocation) -> {
      aroundAdvice.before(instance, method, args);

      Object result = null;
      try {
        result = Utils.invokeMethod(instance, method, args);
      } finally {
        aroundAdvice.after(instance, method, args, result);
      }
      return result;
    });
  }

//  public <T> T createProxy(Class<T> type, T delegate) {
//    requireNonNull(type);
//    requireNonNull(delegate);
//
//    return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
//            new Class<?>[] { type },
//            (proxy, method, args) -> {
//      var advices = findAdvices(method);
//      for(var advice: advices) {
//        advice.before(delegate, method, args);
//      }
//
//      Object result = null; // on doit init avec une valeur par défaut car si une exception pète dans Utils.invokeMethod(delegate, method, args), alors result ne sera pas initialisé et le code du finally ne compilera pas
//      try {
//        result = Utils.invokeMethod(delegate, method, args);
//      } finally {
//        for(var advice: Utils.reverseList(advices)) {
//          advice.after(delegate, method, args, result);
//        }
//      }
//      return result;
//    }));
//  }

  public <T> T createProxy(Class<T> type, T instance) {
    requireNonNull(type);
    requireNonNull(instance);

    return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
            new Class<?>[] { type },
            (proxy, method, args) -> {
              // checker le cache
              var invocation = cache.computeIfAbsent(method, m -> {
                var interceptors = findInterceptors(method);
                return getInvocation(interceptors);
              });
              return invocation.proceed(instance, method, args);
            }));
  }
}
