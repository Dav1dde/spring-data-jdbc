/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jdbc.core;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.jdbc.repository.support.SimpleJdbcRepository;
import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.relational.core.sql.Aliased;
import org.springframework.data.relational.core.sql.Column;
import org.springframework.data.relational.core.sql.Expression;
import org.springframework.data.relational.core.sql.SQL;
import org.springframework.data.relational.core.sql.Select;
import org.springframework.data.relational.core.sql.SelectBuilder;
import org.springframework.data.relational.core.sql.StatementBuilder;
import org.springframework.data.relational.core.sql.Table;
import org.springframework.data.relational.core.sql.render.SqlRenderer;
import org.springframework.data.util.Lazy;
import org.springframework.data.util.StreamUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generates SQL statements to be used by {@link SimpleJdbcRepository}
 *
 * @author Jens Schauder
 * @author Yoichi Imai
 * @author Bastian Wilhelm
 * @author Oleksandr Kucher
 */
class SqlGenerator {

	private final RelationalPersistentEntity<?> entity;
	private final RelationalMappingContext context;
	private final List<String> columnNames = new ArrayList<>();
	private final List<String> nonIdColumnNames = new ArrayList<>();
	private final Set<String> readOnlyColumnNames = new HashSet<>();

	private final Lazy<String> findOneSql = Lazy.of(this::createFindOneSelectSql);
	private final Lazy<String> findAllSql = Lazy.of(this::createFindAllSql);
	private final Lazy<String> findAllInListSql = Lazy.of(this::createFindAllInListSql);

	private final Lazy<String> existsSql = Lazy.of(this::createExistsSql);
	private final Lazy<String> countSql = Lazy.of(this::createCountSql);

	private final Lazy<String> updateSql = Lazy.of(this::createUpdateSql);

	private final Lazy<String> deleteByIdSql = Lazy.of(this::createDeleteSql);
	private final Lazy<String> deleteByListSql = Lazy.of(this::createDeleteByListSql);
	private final SqlGeneratorSource sqlGeneratorSource;

	private final Pattern parameterPattern = Pattern.compile("\\W");

	SqlGenerator(RelationalMappingContext context, RelationalPersistentEntity<?> entity,
			SqlGeneratorSource sqlGeneratorSource) {

		this.context = context;
		this.entity = entity;
		this.sqlGeneratorSource = sqlGeneratorSource;
		initColumnNames(entity, "");
	}

	private void initColumnNames(RelationalPersistentEntity<?> entity, String prefix) {

		entity.doWithProperties((PropertyHandler<RelationalPersistentProperty>) property -> {

			// the referencing column of referenced entity is expected to be on the other side of the relation
			if (!property.isEntity()) {
				initSimpleColumnName(property, prefix);
			} else if (property.isEmbedded()) {
				initEmbeddedColumnNames(property, prefix);
			}
		});
	}

	private void initSimpleColumnName(RelationalPersistentProperty property, String prefix) {

		String columnName = prefix + property.getColumnName();

		columnNames.add(columnName);

		if (!entity.isIdProperty(property)) {
			nonIdColumnNames.add(columnName);
		}
		if (property.isAnnotationPresent(ReadOnlyProperty.class)) {
			readOnlyColumnNames.add(columnName);
		}
	}

	private void initEmbeddedColumnNames(RelationalPersistentProperty property, String prefix) {

		final String embeddedPrefix = property.getEmbeddedPrefix();

		final RelationalPersistentEntity<?> embeddedEntity = context.getRequiredPersistentEntity(property.getColumnType());

		initColumnNames(embeddedEntity, prefix + embeddedPrefix);
	}

	/**
	 * Returns a query for selecting all simple properties of an entitty, including those for one-to-one relationhships.
	 * Results are filtered using an {@code IN}-clause on the id column.
	 *
	 * @return a SQL statement. Guaranteed to be not {@code null}.
	 */
	String getFindAllInList() {
		return findAllInListSql.get();
	}

	/**
	 * Returns a query for selecting all simple properties of an entitty, including those for one-to-one relationhships.
	 *
	 * @return a SQL statement. Guaranteed to be not {@code null}.
	 */
	String getFindAll() {
		return findAllSql.get();
	}

	/**
	 * Returns a query for selecting all simple properties of an entity, including those for one-to-one relationships.
	 * Results are limited to those rows referencing some other entity using the column specified by
	 * {@literal columnName}. This is used to select values for a complex property ({@link Set}, {@link Map} ...) based on
	 * a referencing entity.
	 *
	 * @param columnName name of the column of the FK back to the referencing entity.
	 * @param keyColumn if the property is of type {@link Map} this column contains the map key.
	 * @param ordered whether the SQL statement should include an ORDER BY for the keyColumn. If this is {@code true}, the
	 *          keyColumn must not be {@code null}.
	 * @return a SQL String.
	 */
	String getFindAllByProperty(String columnName, @Nullable String keyColumn, boolean ordered) {

		Assert.isTrue(keyColumn != null || !ordered,
				"If the SQL statement should be ordered a keyColumn to order by must be provided.");

		String baseSelect = (keyColumn != null) //
				? createSelectBuilderOld().column(cb -> cb.tableAlias(entity.getTableName()).column(keyColumn).as(keyColumn))
						.build()
				: getFindAll();

		String orderBy = ordered ? " ORDER BY " + keyColumn : "";

		return String.format("%s WHERE %s = :%s%s", baseSelect, columnName, columnName, orderBy);
	}

	String getExists() {
		return existsSql.get();
	}

	String getFindOne() {
		return findOneSql.get();
	}

	String getInsert(Set<String> additionalColumns) {
		return createInsertSql(additionalColumns);
	}

	String getUpdate() {
		return updateSql.get();
	}

	String getCount() {
		return countSql.get();
	}

	String getDeleteById() {
		return deleteByIdSql.get();
	}

	String getDeleteByList() {
		return deleteByListSql.get();
	}

	private String createFindOneSelectSql() {

		return createSelectBuilderOld() //
				.where(wb -> wb.tableAlias(entity.getTableName()).column(entity.getIdColumn()).eq().variable("id")) //
				.build();
	}

	private SelectBuilderOld createSelectBuilderOld() {

		SelectBuilderOld builder = new SelectBuilderOld(entity.getTableName());
		addColumnsForSimpleProperties(entity, "", "", entity, builder);
		addColumnsForEmbeddedProperties(entity, "", "", entity, builder);
		addColumnsAndJoinsForOneToOneReferences(entity, "", "", entity, builder);

		return builder;
	}

	/**
	 * Adds the columns to the provided {@link SelectBuilderOld} representing simple properties, including those from
	 * one-to-one relationships.
	 *
	 * @param rootEntity the root entity for which to add the columns.
	 * @param builder The {@link SelectBuilderOld} to be modified.
	 */
	private void addColumnsAndJoinsForOneToOneReferences(RelationalPersistentEntity<?> entity, String prefix,
			String tableAlias, RelationalPersistentEntity<?> rootEntity, SelectBuilderOld builder) {

		for (RelationalPersistentProperty property : entity) {
			if (!property.isEntity() //
					|| property.isEmbedded() //
					|| Collection.class.isAssignableFrom(property.getType()) //
					|| Map.class.isAssignableFrom(property.getType()) //
			) {
				continue;
			}

			final RelationalPersistentEntity<?> refEntity = context.getRequiredPersistentEntity(property.getActualType());
			final String joinAlias;

			if (tableAlias.isEmpty()) {
				if (prefix.isEmpty()) {
					joinAlias = property.getName();
				} else {
					joinAlias = prefix + property.getName();
				}
			} else {
				if (prefix.isEmpty()) {
					joinAlias = tableAlias + "_" + property.getName();
				} else {
					joinAlias = tableAlias + "_" + prefix + property.getName();
				}
			}

			// final String joinAlias = tableAlias.isEmpty() ? property.getName() : tableAlias + "_" + property.getName();
			builder.join(jb -> jb.leftOuter().table(refEntity.getTableName()).as(joinAlias) //
					.where(property.getReverseColumnName()).eq().column(rootEntity.getTableName(), rootEntity.getIdColumn()));

			addColumnsForSimpleProperties(refEntity, "", joinAlias, refEntity, builder);
			addColumnsForEmbeddedProperties(refEntity, "", joinAlias, refEntity, builder);
			addColumnsAndJoinsForOneToOneReferences(refEntity, "", joinAlias, refEntity, builder);

			// if the referenced property doesn't have an id, include the back reference in the select list.
			// this enables determining if the referenced entity is present or null.
			if (!refEntity.hasIdProperty()) {

				builder.column( //
						cb -> cb.tableAlias(joinAlias) //
								.column(property.getReverseColumnName()) //
								.as(joinAlias + "_" + property.getReverseColumnName()) //
				);
			}
		}
	}

	private void addColumnsForEmbeddedProperties(RelationalPersistentEntity<?> currentEntity, String prefix,
			String tableAlias, RelationalPersistentEntity<?> rootEntity, SelectBuilderOld builder) {
		for (RelationalPersistentProperty property : currentEntity) {
			if (!property.isEmbedded()) {
				continue;
			}

			final String embeddedPrefix = prefix + property.getEmbeddedPrefix();
			final RelationalPersistentEntity<?> embeddedEntity = context
					.getRequiredPersistentEntity(property.getColumnType());

			addColumnsForSimpleProperties(embeddedEntity, embeddedPrefix, tableAlias, rootEntity, builder);
			addColumnsForEmbeddedProperties(embeddedEntity, embeddedPrefix, tableAlias, rootEntity, builder);
			addColumnsAndJoinsForOneToOneReferences(embeddedEntity, embeddedPrefix, tableAlias, rootEntity, builder);
		}
	}

	private void addColumnsForSimpleProperties(RelationalPersistentEntity<?> currentEntity, String prefix,
			String tableAlias, RelationalPersistentEntity<?> rootEntity, SelectBuilderOld builder) {

		for (RelationalPersistentProperty property : currentEntity) {

			if (property.isEntity()) {
				continue;
			}

			final String column = prefix + property.getColumnName();
			final String as = tableAlias.isEmpty() ? column : tableAlias + "_" + column;

			builder.column(cb -> cb //
					.tableAlias(tableAlias.isEmpty() ? rootEntity.getTableName() : tableAlias) //
					.column(column) //
					.as(as));
		}
	}

	private Stream<String> getColumnNameStream(String prefix) {

		return StreamUtils.createStreamFromIterator(entity.iterator()) //
				.flatMap(p -> getColumnNameStream(p, prefix));
	}

	private Stream<String> getColumnNameStream(RelationalPersistentProperty p, String prefix) {

		if (p.isEntity()) {
			return sqlGeneratorSource.getSqlGenerator(p.getType()).getColumnNameStream(prefix + p.getColumnName() + "_");
		} else {
			return Stream.of(prefix + p.getColumnName());
		}
	}

	private String createFindAllSql() {

		Table table = SQL.table(entity.getTableName());

		List<Expression> columnExpressions = new ArrayList<>();
		Set<Join> joinTables = new HashSet<>();
		for (PersistentPropertyPath<RelationalPersistentProperty> path : context
				.findPersistentPropertyPaths(entity.getType(), p -> true)) {

			System.out.println(path);

			// add a join if necessary
			Join join = getJoin(path);
			if (join != null) {
				joinTables.add(join);
			}
			columnExpressions.addAll(getColumn(path));
		}

		SelectBuilder.SelectAndFrom selectBuilder = StatementBuilder.select(columnExpressions);

		SelectBuilder.SelectJoin baseSelect = selectBuilder.from(table);

		// TODO reusing the Join class would be nice
		for (Join join : joinTables) {
			baseSelect = baseSelect.leftOuterJoin(join.joinTable).on(join.joinColumn).equals(join.parentId);
		}

		Select select = baseSelect.build();

		return SqlRenderer.create().render(select);
	}

	List<Column> getColumn(PersistentPropertyPath<RelationalPersistentProperty> path) {

		RelationalPersistentProperty leafProperty = path.getRequiredLeafProperty();

		if (leafProperty.isEmbedded() || containsCollectionOrMap(path)) {
			return Collections.emptyList();
		}

		Table currentTable;
		String columnName;
		if (leafProperty.isEntity()) {
			RelationalPersistentEntity<?> entityType = context.getRequiredPersistentEntity(leafProperty.getActualType());
			if (entityType.hasIdProperty()) {
				return Collections.emptyList();
			}

			currentTable = SQL.table(entityType.getTableName()).as(getAliasPrefix(path));
			columnName = leafProperty.getReverseColumnName();

		} else {

			Table table = SQL.table(entity.getTableName());
			// determine the table the property belongs to
			currentTable = getOwningTable(path, table);
			columnName = getEmbeddedPrefix(path, "") + leafProperty.getColumnName();

		}

		String prefix = getAlias(currentTable);
		if (StringUtils.hasText(prefix)) {
			prefix = prefix + "_";
		}
		return Collections.singletonList(currentTable.column(columnName).as(prefix + columnName));
	}

	private String getAlias(Table currentTable) {
		if (currentTable instanceof Aliased) {
			return ((Aliased) currentTable).getAlias();
		}
		return "";
	}

	private String getEmbeddedPrefix(PersistentPropertyPath<RelationalPersistentProperty> path, String prefix) {

		if (path.getLength() <= 1) {
			return prefix;
		}
		PersistentPropertyPath<RelationalPersistentProperty> parentPath = path.getParentPath();
		RelationalPersistentProperty parentLeaf = parentPath.getRequiredLeafProperty();
		if (!parentLeaf.isEmbedded()) {
			return prefix;
		}
		String embeddedPrefix = parentLeaf.getEmbeddedPrefix();
		return getEmbeddedPrefix(parentPath, embeddedPrefix + prefix);
	}

	/**
	 * returns the table with correct alias which contains the column for the given property path.
	 *
	 * @param path
	 * @param table the table to be used for the empty path.
	 * @return
	 */
	private Table getOwningTable(PersistentPropertyPath<RelationalPersistentProperty> path, Table table) {

		if (path.getLength() > 1) {

			RelationalPersistentProperty leafProperty = path.getRequiredLeafProperty();
			PersistentPropertyPath<RelationalPersistentProperty> parentPath = path.getParentPath();
			if (parentPath.getRequiredLeafProperty().isEmbedded()) {
				return getOwningTable(parentPath, table);
			}
			return SQL.table(leafProperty.getOwner().getTableName()).as(getAliasPrefix(parentPath));
		} else {
			return table;
		}
	}

	@Nullable
	Join getJoin(PersistentPropertyPath<RelationalPersistentProperty> path) {

		RelationalPersistentProperty leafProperty = path.getRequiredLeafProperty();

		if (!leafProperty.isEntity() || leafProperty.isEmbedded() || containsCollectionOrMap(path)) {
			return null;
		}

		Table currentTable = SQL.table(context.getRequiredPersistentEntity(leafProperty.getActualType()).getTableName())
				.as(getAliasPrefix(path));

		PersistentPropertyPath<RelationalPersistentProperty> idDefiningParentPath = getIdDefiningParentPath(path);
		RelationalPersistentEntity<?> owner = getLeafEntity(idDefiningParentPath, entity);
		Table parentTable = SQL.table(owner.getTableName());

		return new Join( //
				currentTable, //
				currentTable.column(path.getRequiredLeafProperty().getReverseColumnName()), //
				parentTable.column(owner.getRequiredIdProperty().getColumnName()) //
		);
	}

	private boolean containsCollectionOrMap(PersistentPropertyPath<RelationalPersistentProperty> path) {

		if (path.isEmpty()) {
			return false;
		}

		return  path.getRequiredLeafProperty().isCollectionLike() //
				|| path.getRequiredLeafProperty().isQualified() //
				|| containsCollectionOrMap(path.getParentPath());
	}

	private RelationalPersistentEntity<?> getLeafEntity(PersistentPropertyPath<RelationalPersistentProperty> path,
			RelationalPersistentEntity<?> root) {
		if (path.getLength() == 0) {
			return root;
		}
		return context.getRequiredPersistentEntity(path.getRequiredLeafProperty().getActualType());
	}

	private PersistentPropertyPath<RelationalPersistentProperty> getIdDefiningParentPath(
			PersistentPropertyPath<RelationalPersistentProperty> path) {

		PersistentPropertyPath<RelationalPersistentProperty> parent = path.getParentPath();
		if (parent.getLength() == 0) {
			return parent;
		}
		if (parent.getRequiredLeafProperty().isEmbedded()) {
			return getIdDefiningParentPath(parent);
		}
		return parent;
	}

	private String getAliasPrefix(PersistentPropertyPath<RelationalPersistentProperty> path) {

		if (path.getLength() == 0) {
			return "";
		}

		PersistentPropertyPath<RelationalPersistentProperty> parentPath = path.getParentPath();

		RelationalPersistentProperty leaf = path.getRequiredLeafProperty();

		String parentAlias = getAliasPrefix(parentPath);
		if (StringUtils.hasText(parentAlias) && !parentPath.getRequiredLeafProperty().isEmbedded()) {
			parentAlias += "_";
		}

		return parentAlias + (leaf.isEmbedded() ? leaf.getEmbeddedPrefix() : leaf.getName());
	}

	private String createFindAllInListSql() {

		return createSelectBuilderOld() //
				.where(wb -> wb.tableAlias(entity.getTableName()).column(entity.getIdColumn()).in().variable("ids")) //
				.build();
	}

	private String createExistsSql() {
		return String.format("SELECT COUNT(*) FROM %s WHERE %s = :id", entity.getTableName(), entity.getIdColumn());
	}

	private String createCountSql() {
		return String.format("select count(*) from %s", entity.getTableName());
	}

	private String createInsertSql(Set<String> additionalColumns) {

		String insertTemplate = "INSERT INTO %s (%s) VALUES (%s)";

		LinkedHashSet<String> columnNamesForInsert = new LinkedHashSet<>(nonIdColumnNames);
		columnNamesForInsert.addAll(additionalColumns);
		columnNamesForInsert.removeIf(readOnlyColumnNames::contains);

		String tableColumns = String.join(", ", columnNamesForInsert);

		String parameterNames = columnNamesForInsert.stream()//
				.map(this::columnNameToParameterName).map(n -> String.format(":%s", n))//
				.collect(Collectors.joining(", "));

		return String.format(insertTemplate, entity.getTableName(), tableColumns, parameterNames);
	}

	private String createUpdateSql() {

		String updateTemplate = "UPDATE %s SET %s WHERE %s = :%s";

		String setClause = columnNames.stream() //
				.filter(s -> !s.equals(entity.getIdColumn())) //
				.filter(s -> !readOnlyColumnNames.contains(s)) //
				.map(n -> String.format("%s = :%s", n, columnNameToParameterName(n))) //
				.collect(Collectors.joining(", "));

		return String.format( //
				updateTemplate, //
				entity.getTableName(), //
				setClause, //
				entity.getIdColumn(), //
				columnNameToParameterName(entity.getIdColumn()) //
		);
	}

	private String createDeleteSql() {
		return String.format("DELETE FROM %s WHERE %s = :id", entity.getTableName(), entity.getIdColumn());
	}

	String createDeleteAllSql(@Nullable PersistentPropertyPath<RelationalPersistentProperty> path) {

		if (path == null) {
			return String.format("DELETE FROM %s", entity.getTableName());
		}

		RelationalPersistentEntity<?> entityToDelete = context
				.getRequiredPersistentEntity(path.getRequiredLeafProperty().getActualType());

		final String innerMostCondition1 = createInnerMostCondition("%s IS NOT NULL", path);
		String condition = cascadeConditions(innerMostCondition1, getSubPath(path));

		return String.format("DELETE FROM %s WHERE %s", entityToDelete.getTableName(), condition);
	}

	private String createDeleteByListSql() {
		return String.format("DELETE FROM %s WHERE %s IN (:ids)", entity.getTableName(), entity.getIdColumn());
	}

	String createDeleteByPath(PersistentPropertyPath<RelationalPersistentProperty> path) {

		RelationalPersistentEntity<?> entityToDelete = context
				.getRequiredPersistentEntity(path.getRequiredLeafProperty().getActualType());

		final String innerMostCondition = createInnerMostCondition("%s = :rootId", path);
		String condition = cascadeConditions(innerMostCondition, getSubPath(path));

		return String.format("DELETE FROM %s WHERE %s", entityToDelete.getTableName(), condition);
	}

	private String createInnerMostCondition(String template, PersistentPropertyPath<RelationalPersistentProperty> path) {
		PersistentPropertyPath<RelationalPersistentProperty> currentPath = path;
		while (!currentPath.getParentPath().isEmpty()
				&& !currentPath.getParentPath().getRequiredLeafProperty().isEmbedded()) {
			currentPath = currentPath.getParentPath();
		}

		RelationalPersistentProperty property = currentPath.getRequiredLeafProperty();
		return String.format(template, property.getReverseColumnName());
	}

	private PersistentPropertyPath<RelationalPersistentProperty> getSubPath(
			PersistentPropertyPath<RelationalPersistentProperty> path) {

		int pathLength = path.getLength();

		PersistentPropertyPath<RelationalPersistentProperty> ancestor = path;

		int embeddedDepth = 0;
		while (!ancestor.getParentPath().isEmpty() && ancestor.getParentPath().getRequiredLeafProperty().isEmbedded()) {
			embeddedDepth++;
			ancestor = ancestor.getParentPath();
		}

		ancestor = path;

		for (int i = pathLength - 1 + embeddedDepth; i > 0; i--) {
			ancestor = ancestor.getParentPath();
		}

		return path.getExtensionForBaseOf(ancestor);
	}

	private String cascadeConditions(String innerCondition, PersistentPropertyPath<RelationalPersistentProperty> path) {

		if (path.getLength() == 0) {
			return innerCondition;
		}

		PersistentPropertyPath<RelationalPersistentProperty> rootPath = path;
		while (rootPath.getLength() > 1) {
			rootPath = rootPath.getParentPath();
		}

		RelationalPersistentEntity<?> entity = context
				.getRequiredPersistentEntity(rootPath.getBaseProperty().getOwner().getTypeInformation());
		RelationalPersistentProperty property = path.getRequiredLeafProperty();

		return String.format("%s IN (SELECT %s FROM %s WHERE %s)", //
				property.getReverseColumnName(), //
				entity.getIdColumn(), //
				entity.getTableName(), innerCondition //
		);
	}

	private String columnNameToParameterName(String columnName) {
		return parameterPattern.matcher(columnName).replaceAll("");
	}

	@Value
	class Join {
		Table joinTable;
		Column joinColumn;
		Column parentId;
	}

}
