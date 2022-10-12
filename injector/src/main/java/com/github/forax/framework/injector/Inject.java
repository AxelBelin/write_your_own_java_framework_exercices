package com.github.forax.framework.injector;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME) // juste pour le compilo ou en runtime avec RUNTIME Ex : @Override a une retention de type SOURCe car elle existe pas en RUNTIME
// Il y a 3 types de Retention : RUNTIME, SOURCE et CLASS
@Target({METHOD, CONSTRUCTOR}) // Sur quoi j'ai le droit de mettre l'annotation : ici que sur method ou constructeur
public @interface Inject { }
