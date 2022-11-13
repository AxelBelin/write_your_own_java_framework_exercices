package com.github.forax.framework.orm;

import java.util.Collections;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Stream;

public final class ORM {
  private static final ThreadLocal<Connection> DATA_THREAD_LOCAL = new ThreadLocal<>();
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) { // pour récup le type param de la générique interface
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  static String findTableName(Class<?> beanClass) {
    var annotation = beanClass.getAnnotation(Table.class);
    if(annotation != null) {
      return annotation.value();
   }

    return beanClass.getSimpleName().toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) { // property = propriete du bean
    var getter = property.getReadMethod();
    var annotation = getter.getAnnotation(Column.class);
    if(annotation != null) {
      return annotation.value();
    }

    return property.getName().toUpperCase(Locale.ROOT);
  }

  private static String resolveType(PropertyDescriptor property) {
    var type = property.getPropertyType();
    var sqlType = TYPE_MAPPING.get(type);
    if(sqlType == null) {
      throw new IllegalStateException("unknown property type " + property);
    }

    var nullable = type.isPrimitive() ? " NOT NULL" : "";
    var isAutoIncrement = property.getReadMethod().isAnnotationPresent(GeneratedValue.class);
    var autoIncrement = isAutoIncrement ? " AUTO_INCREMENT" : "";
    return sqlType + nullable + autoIncrement;
  }

  private static boolean isID(PropertyDescriptor property) {
    return property.getReadMethod().isAnnotationPresent(Id.class);
  }

  private static String parseProperties(Class<?> beanClass) {
    var joiner = new StringJoiner(",\n");
    for (var property : Utils.beanInfo(beanClass).getPropertyDescriptors()) {
      if (property.getName().equals("class")) {
        continue;
      }

      //FIXME
//      if (property.getWriteMethod() == null) {
//        throw new IllegalStateException("no setter for " + property.getName());
//      }

      var fieldName = findColumnName(property);
      var field = fieldName + " " + resolveType(property);
      joiner.add(field);
      if(isID(property)) {
        joiner.add("PRIMARY KEY (" + fieldName +")");
      }
    }
    return joiner.toString();
  }

  private static List<PropertyDescriptor> beanProperties(BeanInfo beanInfo) {
    var properties = beanInfo.getPropertyDescriptors();
    if(properties == null) {
      throw new IllegalStateException();
    }

    return List.of(properties);
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = beanProperties(beanInfo);
    var index = 1;
    for(var property: properties) {
      if(property.getName().equals("class")) {
        continue;
      }
      var setter = property.getWriteMethod();
      if(setter != null) {
        var columnValue = resultSet.getObject(index); // On connait déjà l'ordre dans lequel les types vont arriver car c'est l'ordre dans lequel la table a été crée.
        // Comme c'est nous qui avons créé la table alors on connait déjà l'ordre et on sait qu'il est bon.
        Utils.invokeMethod(instance, setter, columnValue);
      }

      index += 1;
    }

    return instance;
  }

  static List<?> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor,  Object...args)
          throws SQLException {
    var instances = new ArrayList<>();
    try(var statement = connection.prepareStatement(sqlQuery)) {
      if(args != null) {
        for(var i = 0; i < args.length; i++) {
          statement.setObject(i + 1, args[i]);
        }
      }
      try(var resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          instances.add(toEntityClass(resultSet, beanInfo, constructor));
        }
      }
    }

    return instances;
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var properties = beanInfo.getPropertyDescriptors();
    var columnNames = Arrays.stream(properties)
            .map(PropertyDescriptor::getName)
            .filter(Predicate.not("class"::equals))
            .collect(Collectors.joining(", "));

    var jokers = String.join(", ", Collections.nCopies(properties.length - 1, "?"));
    return """
            MERGE INTO %s (%s) VALUES (%s);""".formatted(tableName, columnNames, jokers);
  }

  static PropertyDescriptor findId(BeanInfo beanInfo) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .filter(property -> property.getReadMethod().isAnnotationPresent(Id.class))
            .findFirst()
            .orElse(null);
  }

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String propertyName) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .filter(property -> property.getName().equals(propertyName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No property exists with this name"));
  }

  static Object save(Connection connection, String tableName, BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) throws SQLException {
    var sqlQuery = createSaveQuery(tableName, beanInfo);
    try(var statement = connection.prepareStatement(sqlQuery, Statement.RETURN_GENERATED_KEYS)) {
      var index = 1;
      for(var property: beanInfo.getPropertyDescriptors()) {
        if(property.getName().equals("class")) {
          continue;
        }

        var getter = property.getReadMethod();
        var value = Utils.invokeMethod(bean, getter);
        statement.setObject(index, value);
        index += 1;
      }

      statement.executeUpdate();

      if(idProperty != null) {
        try(var resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            var key = resultSet.getObject(1);
            var setter = idProperty.getWriteMethod();
            Utils.invokeMethod(bean, setter, key);
          }
        }
      }
    }

    return bean;
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var connection = currentConnection();
    var name = findTableName(beanClass);
    var query = """
    CREATE TABLE %s (
      %s
    );
   """.formatted(name, parseProperties(beanClass));

    try(Statement statement = connection.createStatement()) {
      statement.executeUpdate(query);
    }

    connection.commit();
  }

  static Connection currentConnection() {
    var connexion = DATA_THREAD_LOCAL.get();

    if(connexion == null) {
      throw new IllegalStateException("no connection available");
    }

    return connexion;
  }

  public static void transaction(DataSource dataSource, TransactionBlock block)
          throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);

    try(var connection = dataSource.getConnection()) {
      DATA_THREAD_LOCAL.set(connection);
      connection.setAutoCommit(false);
      try {
        try {
          block.run();
        } catch (UncheckedSQLException e) {
          throw e.getCause();
        }
      } catch(SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch(SQLException e2) {
          e.addSuppressed(e2);
        }

        throw e;
      } finally {
        connection.commit();
        DATA_THREAD_LOCAL.remove();
      }
    }
  }

  public static <R extends Repository<T, ID>, T, ID> R createRepository(Class<R> repositoryType) {
    Objects.requireNonNull(repositoryType);
    var beanClass = findBeanTypeFromRepository(repositoryType);
    var beanInfo = Utils.beanInfo(beanClass);
    var defaultConstructor = Utils.defaultConstructor(beanClass);
    var tableName = findTableName(beanClass);

    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (proxy, method, args) -> {
              var methodName = method.getName();
              if(method.getDeclaringClass() == Object.class) { // On sait que il existe au + 1 classe qui a le même nom car Mon classLoader ne permet pas d'avoir 2 objets class différents
                throw new UnsupportedOperationException(methodName + " not supported");
              }

              var connection = currentConnection();
              try {
                var query = method.getAnnotation(Query.class);
                if(query != null) {
                  return findAll(connection, query.value(), beanInfo, defaultConstructor, args);
                }

                return switch (method.getName()) {
                  case "findAll" -> findAll(
                          connection,
                          """
                          SELECT * FROM %s
                          """.formatted(tableName), beanInfo, defaultConstructor);
                  case "findById" -> findAll(
                          connection,
                          """
                           SELECT * FROM %s WHERE %s = ?
                           """.formatted(tableName, findColumnName(findId(beanInfo))),
                          beanInfo,
                          defaultConstructor,
                          args
                  ).stream().findFirst();
                  case "save" -> save(connection, tableName, beanInfo, args[0], findId(beanInfo)); // args[0] = le premier argument de la méthode save().
                  default -> {
                    if(methodName.startsWith("findBy")) {
                      var propertyName = Introspector.decapitalize(methodName.substring("findBy".length()));
                      var property = findProperty(beanInfo, propertyName);
                      yield findAll(
                              connection,
                              """
                              SELECT * FROM %s WHERE %s = ?
                              """.formatted(tableName, findColumnName(property)),
                              beanInfo,
                              defaultConstructor,
                              args
                      ).stream().findFirst();
                    }
                    throw new IllegalStateException(methodName + " not supported");
                  }
                };
              } catch(SQLException e) {
                throw new UncheckedSQLException(e);
              }
    }));
  }
}
