package com.connexta.ddf.catalog.direct;

import static com.connexta.jsonrpc.JsonRpc.INTERNAL_ERROR;
import static com.connexta.jsonrpc.JsonRpc.INVALID_PARAMS;

import com.connexta.jsonrpc.DocMethod;
import com.connexta.jsonrpc.Error;
import com.connexta.jsonrpc.MethodSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.types.Core;
import ddf.catalog.data.types.Security;
import ddf.catalog.federation.FederationException;
import ddf.catalog.filter.FilterBuilder;
import ddf.catalog.operation.CreateRequest;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.impl.CreateRequestImpl;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.source.UnsupportedQueryException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.opengis.filter.Filter;

public class ExtendedMethods implements MethodSet {

  public static final String CLONE_KEY = "ddf.catalog/clone";

  public static final String CREATED_METACARDS_KEY = "createdMetacards";

  private final Map<String, DocMethod> METHODS;

  {
    Builder<String, DocMethod> builder = ImmutableMap.builder();
    builder.put(
        CLONE_KEY,
        new DocMethod(
            this::clone,
            "Takes an id and calls CatalogFramework::query to get the metacard, and creates a clone of the metacard without the security attributes. `params` takes: `id` (Required, value: String)"));
    METHODS = builder.build();
  }

  private List<String> cloneableAssociationAttributes;

  private FilterBuilder filterBuilder;

  private CatalogFramework catalogFramework;

  public ExtendedMethods(FilterBuilder filterBuilder, CatalogFramework catalogFramework) {
    this.filterBuilder = filterBuilder;
    this.catalogFramework = catalogFramework;
  }

  public List<String> getCloneableAssociationAttributes() {
    return cloneableAssociationAttributes;
  }

  public void setCloneableAssociationAttributes(List<String> cloneableAssociationAttributes) {
    this.cloneableAssociationAttributes = cloneableAssociationAttributes;
  }

  @Override
  public Map<String, DocMethod> getMethods() {
    return METHODS;
  }

  private Object clone(Map<String, Object> params) {
    if (!(params.get(Core.ID) instanceof String)) {
      return new Error(INVALID_PARAMS, "id must be provided");
    }

    Object result = doClone((String) params.get(Core.ID));

    if (result instanceof Error) {
      return result;
    }

    return ImmutableMap.of(
        CREATED_METACARDS_KEY,
        ((List<Metacard>) result).stream().map(Utils::metacard2map).collect(Collectors.toList()));
  }

  private Object doClone(String id) {
    Filter idFilter = filterBuilder.attribute(Core.ID).is().equalTo().text(id);
    Filter tagFilter = filterBuilder.attribute(Core.METACARD_TAGS).is().like().text("*");
    Filter filter = filterBuilder.allOf(idFilter, tagFilter);
    Query query = new QueryImpl(filter);

    QueryRequest queryRequest = new QueryRequestImpl(query);

    QueryResponse queryResponse;
    try {
      queryResponse = catalogFramework.query(queryRequest);
    } catch (UnsupportedQueryException | SourceUnavailableException | FederationException e) {
      return new Error(INTERNAL_ERROR, e.getMessage());
    }

    List<Result> results = queryResponse.getResults();

    if (results.size() != 1) {
      return new Error(INTERNAL_ERROR, "No metacard by given id");
    }

    Metacard metacardToClone = results.get(0).getMetacard();
    clearAttributes(
        metacardToClone,
        Arrays.asList(
            Core.ID,
            Core.CREATED,
            Core.MODIFIED,
            Core.METACARD_OWNER,
            Security.ACCESS_ADMINISTRATORS,
            Security.ACCESS_GROUPS,
            Security.ACCESS_GROUPS_READ,
            Security.ACCESS_INDIVIDUALS,
            Security.ACCESS_INDIVIDUALS_READ));
    cloneAttributes(metacardToClone);

    CreateRequest createRequest = new CreateRequestImpl(metacardToClone);

    CreateResponse createResponse;
    try {
      createResponse = catalogFramework.create(createRequest);
    } catch (IngestException | SourceUnavailableException e) {
      return new Error(INTERNAL_ERROR, e.getMessage());
    }

    return createResponse.getCreatedMetacards();
  }

  private void clearAttributes(Metacard metacard, List<String> attributesToClear) {
    attributesToClear.forEach(
        attributeToClear ->
            metacard.setAttribute(new AttributeImpl(attributeToClear, (Serializable) null)));
  }

  private void cloneAttributes(Metacard metacard) {
    for (String attributeName : cloneableAssociationAttributes) {
      Attribute attribute = metacard.getAttribute(attributeName);
      if (attribute == null) {
        continue;
      }

      List<Serializable> values = attribute.getValues();
      List<String> ids =
          values
              .stream()
              .map(Serializable::toString)
              .map(this::doClone)
              .filter(clonedResults -> clonedResults instanceof List)
              .map(clonedResults -> (List<Metacard>) clonedResults)
              .map(this::getClonedMetacardFromList)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
      metacard.setAttribute(new AttributeImpl(attributeName, (Serializable) ids));
    }
  }

  private String getClonedMetacardFromList(List<Metacard> metacards) {
    if (metacards.size() != 1) {
      return null;
    }
    return metacards.get(0).getId();
  }
}