package com.bank.truelayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.Logger;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

//import org.json.simple.JSONObject;
//import org.json.simple.parser.JSONParser;
//import org.json.simple.parser.ParseException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@SpringBootApplication
public class JsonParserApplication {

	public static void main(String[] args) {
		SpringApplication.run(JsonParserApplication.class, args);

		// File file = new File("C:\\Users\\shant\\Downloads\\B4nkd");

		try {

			String fileLocation = "C:/Users/shant/Downloads/B4nkd/ExperianCreditReport/credit-profile-product-apis-v-openapi3.json";
			String fileParentDirectory = fileLocation.substring(0, fileLocation.lastIndexOf("/"));
			System.out.println("File Parent Directory is : " + fileParentDirectory);
			String serviceName = fileParentDirectory.substring(fileParentDirectory.lastIndexOf("/") + 1,
					fileParentDirectory.length());
			System.out.println("Service Name is : " + serviceName);

			File file = new File(fileLocation);
			ObjectMapper objectMapper = new ObjectMapper();
			HashMap<String, HashMap> dataTypes = new HashMap<>();
			HashMap<String, HashMap> endPoints = new HashMap<>();
			HashMap<String, HashMap> endPointsCopy = new HashMap<>();
			HashMap<String, HashMap> rawDataTypes = new HashMap<>();
			HashMap<String, ArrayList> compDataTypes = new HashMap<>();
			HashMap<String, ArrayList> compDataCopy = new HashMap<String, ArrayList>();

			Driver driver = GraphDatabase.driver("bolt://localhost:7687/b4nkd_6",
					AuthTokens.basic("neo4j", "password"));

			Session session = driver.session();

			JsonNode node = objectMapper.readTree(file);
			endpointTraverser(node, endPoints, endPointsCopy);
			schemaRawDataTraverser(node, rawDataTypes);
			schemaCompositeDataTraverser(node, rawDataTypes, compDataTypes, compDataCopy);
			System.out.println(endPoints);

			System.out.println(rawDataTypes);

			// System.out.println(compDataTypes);
			dataTypes.putAll(rawDataTypes);

			// compDataCopy.putAll(compDataTypes);
			// System.out.println(compDataCopy);
			// replace all raw data types in composite data types.
//			compositeDataToRawData(compDataTypes, dataTypes, rawDataTypes);
			compositeDataToRawDataType(compDataTypes, dataTypes, rawDataTypes);

			System.out.println(compDataTypes);
			// System.out.println(dataTypes);

			HashMap finalMap = new HashMap<>();

			Iterator mapIterator = endPoints.keySet().iterator();
			while (mapIterator.hasNext()) {
				String endPointkey = (String) mapIterator.next();
				HashMap reqRes = endPoints.get(endPointkey);
				String requestKey = (String) reqRes.get("request");
				String responseKey = (String) reqRes.get("response");
				reqRes.put("request", dataTypes.get(requestKey));
				reqRes.put("response", dataTypes.get(responseKey));

			}
			System.out.println(endPoints);
			mapIterator = endPoints.keySet().iterator();

			// create the Service Node
			session.run("CREATE (" + serviceName + ":Service {name:\"" + serviceName + "\"}) RETURN " + serviceName);
			// add data to neo4j DB
			addRawDataTypesToNeo4j(session, rawDataTypes);
			addCompositeDataTypesToNeo4j(session, compDataTypes, rawDataTypes);

			// create service endpoints and connect them to data types
			while (mapIterator.hasNext()) {

				String endPointkey = (String) mapIterator.next();

				String endPoint = endPointkey.substring(endPointkey.lastIndexOf("/") + 1, endPointkey.length());
				endPoint = endPoint.replaceAll("-", "");

				HashMap reqRes = endPoints.get(endPointkey);
				HashMap requestKey = (HashMap) reqRes.get("request");
				HashMap responseKey = (HashMap) reqRes.get("response");

				HashMap reqResOrig = endPointsCopy.get(endPointkey);
				String requestKeyOrig = (String) reqResOrig.get("request");
				String responseKeyOrig = (String) reqResOrig.get("response");

				System.out.println(
						" EndPoint " + endPointkey + " Request :  " + requestKey + " Response :  " + responseKey);
				String query = "CREATE (" + endPoint + ":ServiceEndpoint {name: \""+ endPoint +"\", nameurl:\"" + endPointkey + "\" , request:\""
						+ requestKeyOrig + "\", response:\"" + responseKeyOrig + "\" })\n RETURN " + endPoint;
				System.out.println(query);
				session.run(query);
				// create relationship with service for the endpoint
				String relationshipQuery = "MATCH (a:Service), (b:ServiceEndpoint) WHERE  a.name = \"" + serviceName
						+ "\" AND b.name=\"" + endPoint + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
				System.out.println(relationshipQuery);
				session.run(relationshipQuery);

				String requestQueryPart = "";
				String responseQueryPart = "";

				// create request node
				String requestNodequery = "CREATE (" + requestKeyOrig + ":Request {name:\"" + requestKeyOrig
						+ "\" })\n RETURN " + requestKeyOrig;
				System.out.println(requestNodequery);
				session.run(requestNodequery);

				// create a relationship between endpoint and Request object
				String endPointRequestRelationshipQuery = "MATCH (a:ServiceEndpoint), (b:Request) WHERE  a.name = \"" + endPoint
						+ "\" AND b.name=\"" + requestKeyOrig + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
				System.out.println("EndPoint and Request Relationship Query : " + endPointRequestRelationshipQuery);
				session.run(endPointRequestRelationshipQuery);
				
				
				// there are two possibilities here: one is Composite and another is primitive.
				// handle for both.
				// composite
				ArrayList requestParamList = (ArrayList) compDataCopy.get(requestKeyOrig);

				ArrayList relationshipList = new ArrayList();

				if (requestParamList != null) {

					// create relationship with each request node and primitive and composite data
					// types
					for (int i = 0; i < requestParamList.size(); i++) {
						String dataTypeKey = (String) requestParamList.get(i);
						// dataTypeKey = dataTypeKey.replaceAll("-", "_");
						// if raw type
						if (compDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:CompositeData), (b:CompositeData) WHERE  a.name = \""
									+ requestKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						} else if (rawDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:CompositeData), (b:PrimitiveData) WHERE  a.name = \""
									+ requestKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						}

					}
				} else {

					HashMap primtiveDataType = (HashMap) rawDataTypes.get(requestKeyOrig);
					Iterator dataTypeKeyIterator = primtiveDataType.keySet().iterator();
					// create relationship with each request node and primitive and composite data
					// types
					while (dataTypeKeyIterator.hasNext()) {

						String dataTypeKey = (String) dataTypeKeyIterator.next();
						// if raw type
						if (compDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:PrimitiveData), (b:CompositeData) WHERE  a.name = \""
									+ requestKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						} else if (rawDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:PrimitiveData), (b:PrimitiveData) WHERE  a.name = \""
									+ requestKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						}
					}
				}
				// create response node
				String responseNodequery = "CREATE (" + responseKeyOrig + ":Response {name:\"" + responseKeyOrig
						+ "\" })\n RETURN " + responseKeyOrig;
				System.out.println(responseNodequery);
				session.run(responseNodequery);
				
				// create a relationship between endpoint and Request object
				String endPointResponseRelationshipQuery = "MATCH (a:ServiceEndpoint), (b:Request) WHERE  a.name = \"" + endPoint
						+ "\" AND b.name=\"" + responseKeyOrig + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
				System.out.println("EndPoint and Request Relationship Query : " + endPointResponseRelationshipQuery);
				session.run(endPointResponseRelationshipQuery);				
				
				ArrayList responseParamList = (ArrayList) compDataCopy.get(responseKeyOrig);
				
				// create relationship with each request node and primitive and composite data
				// types
				
				if (responseParamList != null) {

					// create relationship with each request node and primitive and composite data
					// types
					for (int i = 0; i < responseParamList.size(); i++) {
						String dataTypeKey = (String) responseParamList.get(i);
						// dataTypeKey = dataTypeKey.replaceAll("-", "_");
						// if raw type
						if (compDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:CompositeData), (b:CompositeData) WHERE  a.name = \""
									+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						} else if (rawDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:CompositeData), (b:PrimitiveData) WHERE  a.name = \""
									+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						}

					}
				} else {

					HashMap primtiveDataType = (HashMap) rawDataTypes.get(requestKeyOrig);
					Iterator dataTypeKeyIterator = primtiveDataType.keySet().iterator();
					// create relationship with each request node and primitive and composite data
					// types
					while (dataTypeKeyIterator.hasNext()) {

						String dataTypeKey = (String) dataTypeKeyIterator.next();
						// if raw type
						if (compDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:PrimitiveData), (b:CompositeData) WHERE  a.name = \""
									+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						} else if (rawDataTypes.get(dataTypeKey) != null) {
							String reqRelationshipQuery = "MATCH (a:PrimitiveData), (b:PrimitiveData) WHERE  a.name = \""
									+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
									+ "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
							relationshipList.add(reqRelationshipQuery);
						}
					}
				}
				
//				for (int i = 0; i < responseParamList.size(); i++) {
//					String dataTypeKey = (String) responseParamList.get(i);
//					// dataTypeKey = dataTypeKey.replaceAll("-", "_");
//					// if raw type
//					if (compDataTypes.get(dataTypeKey) != null) {
//						String resRelationshipQuery = "MATCH (a:CompositeData), (b:PrimitiveData) WHERE  a.name = \""
//								+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
//								+ "CREATE (a) -[r:USES]-> (b)" + "RETURN a, b";
//						relationshipList.add(resRelationshipQuery);
//					} else if (rawDataTypes.get(dataTypeKey) != null) {
//						String resRelationshipQuery = "MATCH (a:PrimitiveData), (b:PrimitiveData) WHERE  a.name = \""
//								+ responseKeyOrig + "\" AND b.name=\"" + dataTypeKey + "\" "
//								+ "CREATE (a) -[r:USES]-> (b)" + "RETURN a, b";
//						relationshipList.add(resRelationshipQuery);
//					}
//				}
				System.out.println("Relationships for " + endPoint);
				for (int i = 0; i < relationshipList.size(); i++) {
					System.out.println(
							"Relationships for " + endPoint + " : Query " + relationshipList.get(i).toString());
					session.run(relationshipList.get(i).toString());
				}
			}

			session.close();

			driver.close();

			FileWriter writer = new FileWriter(new File("D://log.txt"));
			writer.write(dataTypes.toString());
			writer.close();

		} catch (JsonProcessingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		// deleteJsonFiles(file);
		// yamlToJson(file);

	}

	public static void addRawDataTypesToNeo4j(Session graphDb, HashMap<String, HashMap> rawDataTypes) {

		Iterator iterator = rawDataTypes.keySet().iterator();

		while (iterator.hasNext()) {

//			Map.Entry<String, HashMap> pair = (Map.Entry) iterator.next();

			String key = (String) iterator.next();
			HashMap map = rawDataTypes.get(key);
//			// non -composite primary data type
//			if (map.get(key) != null) {
//
//				
//				HashMap primitiveMap = (HashMap)map.get(key);
			Iterator mapIterator = map.keySet().iterator();
			String queryPart = "";
			while (mapIterator.hasNext()) {
				String dataTypeKey = (String) mapIterator.next();
				queryPart = queryPart + ", " + dataTypeKey + ":\"" + map.get(dataTypeKey) + "\"";
			}
			key = key.replaceAll("-", "_");

			String query = "CREATE (" + key + ":PrimitiveData {name:\"" + key + "\"" + queryPart + "})\n" + "RETURN "
					+ key;
			System.out.println(query);
			graphDb.run(query);

		}
	}

	public static void addCompositeDataTypesToNeo4j(Session graphDb, HashMap<String, ArrayList> compDataTypes,
			HashMap rawDataType) {

		Iterator iterator = compDataTypes.keySet().iterator();
		ArrayList relationshipList = new ArrayList();

		while (iterator.hasNext()) {

			String key = (String) iterator.next();
			ArrayList list = (ArrayList) compDataTypes.get(key);
			String queryPart = "";
			for (int i = 0; i < list.size(); i++) {
				String dataTypeKey = (String) list.get(i);

				String dataTypeKeyModified = dataTypeKey.replaceAll("-", "");
				if (compDataTypes.get(key)!= null) {
					if (compDataTypes.get(dataTypeKey) != null) {
						String relationshipQuery = "MATCH (a:CompositeData), (b:CompositeData) WHERE  a.name = \"" + key
								+ "\" AND b.name=\"" + dataTypeKey + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
						relationshipList.add(relationshipQuery);
					} else if (rawDataType.get(dataTypeKey) != null) {
						String relationshipQuery = "MATCH (a:CompositeData), (b:PrimitiveData) WHERE  a.name = \"" + key
								+ "\" AND b.name=\"" + dataTypeKey + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
						relationshipList.add(relationshipQuery);
					}	
				}
				else if(rawDataType.get(key) != null) {
					if (compDataTypes.get(dataTypeKey) != null) {
						String relationshipQuery = "MATCH (a:PrimitiveData), (b:CompositeData) WHERE  a.name = \"" + key
								+ "\" AND b.name=\"" + dataTypeKey + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
						relationshipList.add(relationshipQuery);
					} else if (rawDataType.get(dataTypeKey) != null) {
						String relationshipQuery = "MATCH (a:PrimitiveData), (b:PrimitiveData) WHERE  a.name = \"" + key
								+ "\" AND b.name=\"" + dataTypeKey + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
						relationshipList.add(relationshipQuery);
					}
				}
				// if raw type
				
				queryPart = queryPart + ", " + dataTypeKeyModified + ":\"" + dataTypeKey + "\"";

			}
			key = key.replaceAll("-", "_");

			String query = "CREATE (" + key + ":CompositeData {name:\"" + key + "\"" + queryPart + "})\n" + "RETURN "
					+ key;
			System.out.println(query);

			graphDb.run(query);

		}
		for (int i = 0; i < relationshipList.size(); i++) {
			System.out.println("Composite Data Type Relationship Query : " + relationshipList.get(i).toString());
			graphDb.run(relationshipList.get(i).toString());
		}
	}

//	public static void addServiceParametersToNeo4j(Session graphDb, HashMap<String, ArrayList> compDataTypes,
//			HashMap rawDataType) {
//
//		Iterator iterator = compDataTypes.keySet().iterator();
//		ArrayList relationshipList = new ArrayList();
//
//		while (iterator.hasNext()) {
//
//			String key = (String) iterator.next();
//			ArrayList list = (ArrayList) compDataTypes.get(key);
//			String queryPart = "";
//			for (int i = 0; i < list.size(); i++) {
//				String dataTypeKey = (String) list.get(i);
//				dataTypeKey = dataTypeKey.replaceAll("-", "");
//				// if raw type
//				if (rawDataType.get(dataTypeKey) != null || compDataTypes.get(dataTypeKey) != null) {
//					String relationshipQuery = "MATCH (a:CompositeData), (b:PrimitiveData) WHERE  a.name = \"" + key
//							+ "\" AND b.name=\"" + dataTypeKey + "\" " + "CREATE (a)-[r:USES]->(b)" + "RETURN a, b";
//					relationshipList.add(relationshipQuery);
//				}
//				queryPart = queryPart + ", " + dataTypeKey + ":\"" + dataTypeKey + "\"";
//
//			}
//			key = key.replaceAll("-", "");
//
//			String query = "CREATE (" + key + ":CompositeData {name:\"" + key + "\"" + queryPart + "})\n" + "RETURN "
//					+ key;
//			System.out.println(query);
//
//			graphDb.run(query);
//
//		}
//		for (int i = 0; i < relationshipList.size(); i++) {
//			graphDb.run(relationshipList.get(i).toString());
//		}
//	}

	public static void compositeDataToRawData(HashMap compDataTypes, HashMap dataTypes, HashMap rawDataTypes) {
		Iterator iterator = compDataTypes.entrySet().iterator();
		while (iterator.hasNext()) {
			HashMap dataTypesMap = new HashMap();
			Map.Entry<String, ArrayList> pair = (Map.Entry) iterator.next();
			String key = pair.getKey();
			ArrayList list = pair.getValue();
			for (int i = 0; i < list.size(); i++) {
				String ref = (String) list.get(i);
				// check if it is in raw data types
				if (rawDataTypes.get(ref) != null) {
					dataTypesMap.put(ref, rawDataTypes.get(ref));
				}
				if (compDataTypes.get(ref) != null) {
					HashMap compositeRawMap = new HashMap();
					ArrayList dataList = (ArrayList) compDataTypes.get(ref);
//					if (ref.indexOf("PII") != -1) {
//						System.out.println(compDataTypes.get(ref));
//					}
					for (int j = 0; j < dataList.size(); j++) {
						String refDataType = (String) dataList.get(j);
						// System.out.println(refDataType + " : " + rawDataTypes.get(refDataType));
						// System.out.println(refDataType + " : " + compDataTypes.get(refDataType));
						// check if it is in raw data types
						if (rawDataTypes.get(refDataType) != null) {

							compositeRawMap.put(refDataType, rawDataTypes.get(refDataType));
						}
//						else if (compDataTypes.get(refDataType) != null) {
//							compositeRawMap.put(refDataType, compDataTypes.get(refDataType));
//						} 
						else {
							compositeRawMap.put(refDataType, refDataType);
						}

					}
					dataTypesMap.put(ref, compositeRawMap);
				}
			}
			dataTypes.put(key, dataTypesMap);
		}
	}

	/**
	 * 
	 * @param compDataTypes
	 * @param dataTypes
	 * @param rawDataTypes
	 */
	public static void compositeDataToRawDataType(HashMap compDataTypes, HashMap dataTypes, HashMap rawDataTypes) {
		Iterator iterator = compDataTypes.entrySet().iterator();
		while (iterator.hasNext()) {
			HashMap dataTypesMap = new HashMap();
			Map.Entry<String, ArrayList> pair = (Map.Entry) iterator.next();
			String key = pair.getKey();
			ArrayList list = pair.getValue();
			for (int i = 0; i < list.size(); i++) {
				String ref = (String) list.get(i);
				dataTypesMap.put(ref, ref);
			}
			dataTypes.put(key, dataTypesMap);
		}
	}

	public static void compositeDataTransform(HashMap compDataCopy, HashMap rawDataTypes) {
		Iterator iterator = compDataCopy.entrySet().iterator();
		while (iterator.hasNext()) {
			HashMap dataTypesMap = new HashMap();
			Map.Entry<String, ArrayList> pair = (Map.Entry) iterator.next();
			String key = pair.getKey();
			ArrayList list = pair.getValue();
			for (int i = 0; i < list.size(); i++) {
				String ref = (String) list.get(i);
				// check if it is in raw data types
				if (rawDataTypes.get(ref) != null) {
					dataTypesMap.put(ref, rawDataTypes.get(ref));
				}
				if (compDataCopy.get(ref) != null) {
					HashMap compositeRawMap = new HashMap();
					ArrayList dataList = (ArrayList) compDataCopy.get(ref);
//					if (ref.indexOf("PII") != -1) {
//						System.out.println(compDataTypes.get(ref));
//					}
					for (int j = 0; j < dataList.size(); j++) {
						String refDataType = (String) dataList.get(j);
						// System.out.println(refDataType + " : " + rawDataTypes.get(refDataType));
						// System.out.println(refDataType + " : " + compDataTypes.get(refDataType));
						// check if it is in raw data types
						if (rawDataTypes.get(refDataType) != null) {

							compositeRawMap.put(refDataType, rawDataTypes.get(refDataType));
						} else {
							compositeRawMap.put(refDataType, compDataCopy.get(refDataType));
						}

					}
					dataTypesMap.put(ref, compositeRawMap);
				}
			}
		}
	}

	public static void endpointTraverser(JsonNode node, HashMap endpoints, HashMap endpointsCopy) {

		JsonNode paths = node.get("paths");

		Iterator<Entry<String, JsonNode>> nodes = paths.fields();
		while (nodes.hasNext()) {

			Map.Entry<String, JsonNode> pathEntry = (Map.Entry<String, JsonNode>) nodes.next();

			// System.out.println("key --> " + pathEntry.getKey() + " value-->" +
			// pathEntry.getValue());
			HashMap reqRespMap = new HashMap();
			HashMap reqRespMapCopy = new HashMap();

			// extract Request body
			JsonNode requestSchemaNode = pathEntry.getValue().path("post").path("requestBody").path("content")
					.path("application/json").path("schema");
			Iterator<Entry<String, JsonNode>> schemaNodes = requestSchemaNode.fields();
			while (schemaNodes.hasNext()) {
				Map.Entry<String, JsonNode> schemaEntry = (Map.Entry<String, JsonNode>) schemaNodes.next();

				// System.out.println("\tRequest - key --> " + schemaEntry.getKey() + "
				// value-->" + schemaEntry.getValue());

				String reference = schemaEntry.getValue().toString().replaceAll("-", "");

				String value = reference.substring(reference.lastIndexOf("/") + 1, reference.length() - 1)
						.replaceAll("-", "");
				//System.out.println("Request : " + value);
				reqRespMap.put("request", value);
				reqRespMapCopy.put("request", value);
			}
			// extract Response body
			JsonNode responsetSchemaNode = pathEntry.getValue().path("post").path("responses").path("200")
					.path("content").path("application/json").path("schema");
			schemaNodes = responsetSchemaNode.fields();
			while (schemaNodes.hasNext()) {
				Map.Entry<String, JsonNode> schemaEntry = (Map.Entry<String, JsonNode>) schemaNodes.next();

				// System.out.println("\tResponse - key --> " + schemaEntry.getKey() + "
				// value-->" + schemaEntry.getValue());
				String reference = schemaEntry.getValue().toString().replaceAll("-", "");
				String value = reference.substring(reference.lastIndexOf("/") + 1, reference.length() - 1)
						.replaceAll("-", "");
				//System.out.println("Response : " + value);
				reqRespMap.put("response", value);
				reqRespMapCopy.put("response", value);
			}

			endpoints.put(pathEntry.getKey(), reqRespMap);
			endpointsCopy.put(pathEntry.getKey(), reqRespMapCopy);
		}

	}

	public static void schemaRawDataTraverser(JsonNode node, HashMap rawDataTypes) {
		// get all the root node objects
		JsonNode schemas = node.path("components").path("schemas");

		Iterator<Entry<String, JsonNode>> nodes = schemas.fields();
		while (nodes.hasNext()) {

			Map.Entry<String, JsonNode> pathEntry = (Map.Entry<String, JsonNode>) nodes.next();

			// System.out.println("key --> " + pathEntry.getKey() + " value-->" +
			// pathEntry.getValue());

			JsonNode dataType = pathEntry.getValue();

			// parse through each object to check for $ref node
			// if there is not $ref then put the value in a map
			HashMap dataTypes = new HashMap();
			Iterator<Entry<String, JsonNode>> dataTypePropertiesNodes = dataType.path("properties").fields();
			if (dataTypePropertiesNodes != null) {
				boolean isRef = true;
				while (dataTypePropertiesNodes.hasNext()) {
					Map.Entry<String, JsonNode> schemaEntry = (Map.Entry<String, JsonNode>) dataTypePropertiesNodes
							.next();

					String value = schemaEntry.getValue().toString();
					// pick those data types that don't have $ref of other data types.
					if (!(value.indexOf("$ref") != -1)) {
						isRef = false;
						// System.out.println("Raw Data Types " +
						// schemaEntry.getValue().path("type").asText());

						dataTypes.put(schemaEntry.getKey().replaceAll("-", ""),
								schemaEntry.getValue().path("type").asText().replaceAll("-", ""));
						// System.out.println( "\tProperties - key --> " + schemaEntry.getKey() + "
						// value-->" + schemaEntry.getValue());
					}

				}
				// only stpure Primitive Data Types are collected
				if (!isRef) {
					rawDataTypes.put(pathEntry.getKey(), dataTypes);
				}

			} else {
				// there is not properties tag therefore this is a primitive data type
				rawDataTypes.put(pathEntry.getKey().replaceAll("-", ""),
						new HashMap().put(pathEntry.getKey(), dataType.path("type").asText().replaceAll("-", "")));
			}
		}

	}

	public static void schemaCompositeDataTraverser(JsonNode node, HashMap rawDataTypes, HashMap compDataTypes,
			HashMap compDataCopy) {
		// get all the root node objects
		JsonNode schemas = node.path("components").path("schemas");

		Iterator<Entry<String, JsonNode>> nodes = schemas.fields();
		while (nodes.hasNext()) {

			Map.Entry<String, JsonNode> pathEntry = (Map.Entry<String, JsonNode>) nodes.next();
			ArrayList list = new ArrayList<>();
			ArrayList listCopy = new ArrayList<>();

			if (rawDataTypes.get(pathEntry.getKey()) != null) {
				list.add(pathEntry.getKey().replaceAll("-", ""));
				listCopy.add(pathEntry.getKey().replaceAll("-", ""));
			}
			// this is complext data type
			else {
				// System.out.println("Composite Data key --> " + pathEntry.getKey() + "
				// value-->" + pathEntry.getValue());
				refTraverser(pathEntry.getValue(), list, listCopy, rawDataTypes);
				compDataTypes.put(pathEntry.getKey().replaceAll("-", ""), list);
				compDataCopy.put(pathEntry.getKey().replaceAll("-", ""), listCopy);

			}

		}

	}

	public static void refTraverser(JsonNode node, ArrayList<String> list, ArrayList<String> listCopy,
			HashMap rawDataTypes) {
		Iterator<Entry<String, JsonNode>> nodes = node.fields();
		while (nodes.hasNext()) {

			Map.Entry<String, JsonNode> entry = (Map.Entry<String, JsonNode>) nodes.next();

			// System.out.println("\tRef key --> " + entry.getKey() + " value-->" +
			// entry.getValue());

			// if the data type is a composite
			if (entry.getKey().equals("$ref")) {
				// System.out.println("\tPrinting the $ref node : " +
				// entry.getValue().toString());
				String reference = entry.getValue().toString().replaceAll("-", "");
//				System.out.println(reference.substring(reference.lastIndexOf("/")+1,reference.length()-1));
				String value = reference.substring(reference.lastIndexOf("/") + 1, reference.length() - 1)
						.replaceAll("-", "");
				list.add(value);
				listCopy.add(value);
			}
			// otherwise just add the primitive data type
//			if (rawDataTypes.get(entry.getKey()) != null) {
//			}
			// traverse deeper
			refTraverser(entry.getValue(), list, listCopy, rawDataTypes);
		}

	}

}
