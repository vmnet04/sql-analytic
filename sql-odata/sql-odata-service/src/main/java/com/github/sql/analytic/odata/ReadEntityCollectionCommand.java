package com.github.sql.analytic.odata;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.edm.EdmBindingTarget;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;

import com.github.sql.analytic.expression.BinaryExpression;
import com.github.sql.analytic.expression.Expression;
import com.github.sql.analytic.expression.NamedParameter;
import com.github.sql.analytic.expression.operators.conditional.AndExpression;
import com.github.sql.analytic.expression.operators.relational.EqualsTo;
import com.github.sql.analytic.schema.Column;
import com.github.sql.analytic.schema.Table;
import com.github.sql.analytic.session.SQLSession;
import com.github.sql.analytic.statement.select.FromItem;
import com.github.sql.analytic.statement.select.Join;
import com.github.sql.analytic.statement.select.PlainSelect;
import com.github.sql.analytic.statement.select.Select;
import com.github.sql.analytic.statement.select.SelectExpressionItem;
import com.github.sql.analytic.statement.select.SelectListItem;

public class ReadEntityCollectionCommand{

	private ODataRequest request;
	private ODataResponse response;
	private UriInfo uriInfo;
	private ContentType contentType;
	private EdmEntitySet edmEntitySet;
	private Map<String, Object> statementParams = new HashMap<>();
	private PlainSelect select = new PlainSelect().setSelectItems(new ArrayList<SelectListItem>());
	private int index = 0;
	private EntityCollection collection = new EntityCollection();
	private SelectOption selectOption;
	private SQLSession connection;
	private OData odata;
	private ServiceMetadata metadata;

	public ReadEntityCollectionCommand(ODataRequest request, ODataResponse response, UriInfo uriInfo,
			ContentType contentType) {
		this.request = request;
		this.response = response;
		this.uriInfo = uriInfo;
		this.contentType = contentType;
		this.selectOption = uriInfo.getSelectOption();
	}
	
	public void init(OData odata, ServiceMetadata metadata) {
		this.odata = odata;
		this.metadata = metadata;

	}

	public void execute(SQLSession connection)throws SerializerException, ODataApplicationException, EdmPrimitiveTypeException  {
		
		this.connection = connection;
		
		processUriResource();
		
		Set<String> projection = new HashSet<>(); 

		for(String name : edmEntitySet.getEntityType().getPropertyNames()){
			if(EntityData.inSelection(selectOption,name)){
				SelectExpressionItem item = new SelectExpressionItem().setAlias(name);
				Column column = new Column().
						setTable(new Table(null,edmEntitySet.getEntityType().getName() + "_" + index));			
				select.getSelectItems().add(item.setExpression(column.setColumnName(name)));
				projection.add(name);
			}
		}
		
		try(PreparedStatement statement = connection.create(new Select().setSelectBody(select), statementParams ) ){
			try(ResultSet rs = statement.executeQuery()){		  
				while(rs.next()){
					Entity entity = EntityData.createEntity(edmEntitySet,projection,rs);					
					collection.getEntities().add(entity);
				}
			}	   
		} catch (SQLException | IOException e) {
			throw inernalError(e);
		}
		
		serialize();
	}

	private ODataApplicationException inernalError(Exception e) {
		return new ODataApplicationException(e.getMessage(), HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), 
				Locale.ROOT,e);
	}

	private void processUriResource() throws ODataApplicationException, EdmPrimitiveTypeException {
		
		for(UriResource segment : uriInfo.getUriResourceParts() ){
			index++;
			if(segment instanceof UriResourceNavigation){				
				UriResourceNavigation uriResourceNavigation = (UriResourceNavigation) segment;
				EdmNavigationProperty edmNavigationProperty = uriResourceNavigation.getProperty();	
				EdmEntityType leftType = edmEntitySet.getEntityType();
				edmEntitySet = getNavigationTargetEntitySet(edmNavigationProperty);
				EdmEntityType rightType = edmEntitySet.getEntityType();
				Join join = appendFrom();
				appendOn(join,edmNavigationProperty.getName(),leftType,rightType);
				appendWhere(uriResourceNavigation.getKeyPredicates());
			}else if (segment instanceof UriResourceEntitySet){				
				UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) segment;
				edmEntitySet = uriResourceEntitySet.getEntitySet();			
				appendFrom();
				appendWhere(uriResourceEntitySet.getKeyPredicates());				
			}else {
				throw new ODataApplicationException(segment.toString(), HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), 
						Locale.ROOT);
			}			
		}
	}

	private void serialize() throws SerializerException {
		
		ODataSerializer serializer = odata.createSerializer(contentType);

		String selectList = getSelectList(edmEntitySet, selectOption);

		ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).selectList(selectList).build();

		final String id = request.getRawBaseUri() + "/" + edmEntitySet.getName();
		EntityCollectionSerializerOptions opts = EntityCollectionSerializerOptions.with().id(id).
				select(selectOption).contextURL(contextUrl).build();
		SerializerResult serializerResult = serializer.entityCollection(metadata, edmEntitySet.getEntityType(), collection, opts);
		InputStream serializedContent = serializerResult.getContent();


		response.setContent(serializedContent);
		response.setStatusCode(HttpStatusCode.OK.getStatusCode());
		response.setHeader(HttpHeader.CONTENT_TYPE, contentType.toContentTypeString());
	}

	private String getSelectList(EdmEntitySet edmEntitySet, SelectOption selectOption) throws SerializerException {
		return odata.createUriHelper().buildContextURLSelectList(edmEntitySet.getEntityType(),
				null, selectOption);
	}

	private void appendOn(Join join, String fKname, EdmEntityType leftType, EdmEntityType rightType) throws ODataApplicationException {

		boolean found = false;
		try(ResultSet rs = connection.getMetaData().getImportedKeys(null, rightType.getNamespace(), rightType.getName())){			
			while(rs.next()){
				if(fKname.equalsIgnoreCase(rs.getString(SQLEdmProvider.FK_NAME))){
					found = true;
					setOn(join, rs,index - 1,index);					
				}
			}
			if(!found){
				try(ResultSet right = connection.getMetaData().getExportedKeys(null, rightType.getNamespace(), rightType.getName())){					
					while(right.next()){
						if(fKname.equalsIgnoreCase(right.getString(SQLEdmProvider.FK_NAME))){
							found = true;
							setOn(join, right,index,index - 1 );					
						}
					}
				}
			}

		} catch (SQLException e) {
			throw inernalError(e);
		}		
	}

	private void setOn(Join join, ResultSet rs, int pkIndex,int fkIndex) throws SQLException {

		Table pkTable = new Table(null, rs.getString(SQLEdmProvider.PKTABLE_NAME)+ "_" + pkIndex);
		Column pfCol = new Column(pkTable,rs.getString(SQLEdmProvider.PKCOLUMN_NAME));

		Table fkTable = new Table(null, rs.getString(SQLEdmProvider.FKTABLE_NAME) + "_" + fkIndex);
		Column fkCol = new Column(fkTable,rs.getString(SQLEdmProvider.FKCOLUMN_NAME));

		BinaryExpression eq = new EqualsTo().setLeftExpression(pfCol).setRightExpression(fkCol);

		if(join.getOnExpression() == null ){
			join.setOnExpression(eq);
		}else {
			join.setOnExpression(new AndExpression(join.getOnExpression(),eq));
		}
	}

	private Join appendFrom() {

		FromItem from = select.getFromItem();
		String name = edmEntitySet.getEntityType().getName();
		Table table = new Table(edmEntitySet.getEntityType().getNamespace(),name);
		table.setAlias(name + "_" + index);
		if(from == null){
			select.setFromItem(table);
			return null;
		}else {
			Join join = new Join().setRightItem(table);			
			if(select.getJoins() == null){
				select.setJoins(new ArrayList<Join>());
			}
			select.getJoins().add(join);
			return join;
		}

	}

	private void appendWhere( List<UriParameter> list) throws EdmPrimitiveTypeException {

		Expression expression = null;
		for( UriParameter key :  list){
			expression = appendKey( expression, key );
		}
		if(expression != null){
			Expression where = select.getWhere();
			if(where == null){
				where = expression;
			}else {
				where = new AndExpression(expression, where);
			}
			select.setWhere(where);
		}
	}

	private Expression appendKey(Expression expression, UriParameter key) throws EdmPrimitiveTypeException {

		EdmEntityType entityType =  edmEntitySet.getEntityType();
		String name = entityType.getName() + "_" + index;

		EdmProperty keyProperty = (EdmProperty)entityType.getProperty(key.getName());	
		Object value = getValue(key, keyProperty);
		statementParams.put(name, value);

		Column column = new Column().
				setColumnName(key.getName()).
				setTable(new Table().setName(name));

		BinaryExpression eq = new EqualsTo().
				setLeftExpression(column).
				setRightExpression(new NamedParameter(name));

		if(expression == null){							
			expression = eq;
		}else {
			expression = new AndExpression(expression, eq);
		}
		return expression;
	}

	private Object getValue(UriParameter key, EdmProperty keyProperty) throws EdmPrimitiveTypeException {

		EdmPrimitiveType type = (EdmPrimitiveType) keyProperty.getType();
		Object value = type.valueOfString(key.getText(), 
				keyProperty.isNullable(),
				keyProperty.getMaxLength(),
				keyProperty.getPrecision(), 
				keyProperty.getScale(), true, type.getDefaultType());

		return value;
	}

	public EdmEntitySet getNavigationTargetEntitySet(EdmNavigationProperty edmNavigationProperty)
					throws ODataApplicationException {

		if ( edmEntitySet == null) {
			throw notImplemented();
		}

		EdmEntitySet navigationTargetEntitySet = null;

		String navPropName = edmNavigationProperty.getName();
		EdmBindingTarget edmBindingTarget = edmEntitySet.getRelatedBindingTarget(navPropName);

		if (edmBindingTarget == null) {
			throw notImplemented();
		}

		if (edmBindingTarget instanceof EdmEntitySet) {
			navigationTargetEntitySet = (EdmEntitySet) edmBindingTarget;
		} else {
			throw notImplemented();
		}

		return navigationTargetEntitySet;
	}

	private ODataApplicationException notImplemented() {
		return new ODataApplicationException("Not supported.",
				HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ROOT);
	}
}