package com.github.forax.framework.orm;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Optional;
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

  private static <T> T toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<T> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = beanProperties(beanInfo);
    var i = 1;

    for(var property: properties) {
      var setter = property.getWriteMethod();
      var argumentType = setter.getParameterTypes()[0];
      var columnValue = argumentType.cast(resultSet.getObject(i));
      Utils.invokeMethod(instance, setter, columnValue);
      i += 1;
    }

    return instance;
  }

  // TODO findAll(connection, sqlQuery, beanInfo, constructor)

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var connection = currentConnection();
    var name = findTableName(beanClass);


//    var properties = Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
//            .filter(property -> !property.getName().equals("class"))
//            .map(property -> findColumnName(property) + " VARCHAR(255)").collect(Collectors.joining(",\n"));
//    var query = """
//    CREATE TABLE %s (
//      %s
//    );
//   """.formatted(name, properties);

//    var properties = Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
//            .filter(property -> !property.getName().equals("class"))
//            .map(property -> findColumnName(property) + " " + resolveType(property)).collect(Collectors.joining(",\n"));
//    var query = """
//    CREATE TABLE %s (
//      %s
//    );
//   """.formatted(name, properties);

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

//    try(var connection = dataSource.getConnection()) {
//      DATA_THREAD_LOCAL.set(connection);
//      try {
//        block.run();
//      } catch(SQLException | RuntimeException e) {
//        connection.rollback();
//        throw e; // je propage l'exception : ca marche c'est soit une SQLException soit une RuntimeException et les SQL sont propagées et les Runtime on est pas onligé de les catch
//        // On en doit pas faire ça : throw new SQLException() : car on aura perdu toutes les infos de la nature de l'esception vu que ca peut être aussi une Runtime
//      } finally {
//        DATA_THREAD_LOCAL.remove();
//      }
//    }

    try(var connection = dataSource.getConnection()) {
      DATA_THREAD_LOCAL.set(connection);
      connection.setAutoCommit(false);
      try {
        block.run();
      } catch(SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch(SQLException e2) {
          e.addSuppressed(e2); // On garde les 2 info dans les logs : e1 + e2
        }

        throw e; // je propage l'exception : ca marche c'est soit une SQLException soit une RuntimeException et les SQL sont propagées et les Runtime on est pas onligé de les catch
        // On en doit pas faire ça : throw new SQLException() : car on aura perdu toutes les infos de la nature de l'esception vu que ca peut être aussi une Runtime
      } finally {
        connection.commit();
        DATA_THREAD_LOCAL.remove();
      }
    }
  }

  public static <T, ID> Repository<T, ID> createRepository(Class<T> repositoryType) {
    Objects.requireNonNull(repositoryType);
    return new Repository<>() {
      @Override
      public List<T> findAll() {
        var connection = currentConnection();
        return List.of();
      }

      @Override
      public Optional<T> findById(ID id) {
        throw new IllegalStateException();
      }

      @Override
      public T save(T entity) {
        throw new IllegalStateException();
      }

      @Override
      public boolean equals(Object o) {
        throw new UnsupportedOperationException();
      }

      @Override
      public int hashCode() {
        throw new UnsupportedOperationException();
      }

      @Override
      public String toString() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
