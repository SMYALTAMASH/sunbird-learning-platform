package org.ekstep.searchindex.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.ekstep.common.slugs.Slug;
import org.ekstep.common.util.AWSUploader;
import org.ekstep.common.util.S3PropertyReader;
import org.ekstep.content.enums.ContentWorkflowPipelineParams;
import org.ekstep.learning.common.enums.ContentAPIParams;
import org.ekstep.learning.util.ControllerUtil;

import com.ilimi.common.dto.Response;
import com.ilimi.common.logger.LoggerEnum;
import com.ilimi.common.logger.PlatformLogger;
import com.ilimi.graph.dac.enums.GraphDACParams;
import com.ilimi.graph.dac.enums.RelationTypes;
import com.ilimi.graph.dac.model.Node;
import com.ilimi.graph.dac.model.Relation;
import com.ilimi.graph.enums.CollectionTypes;

/**
 * The Class ContentEnrichmentMessageProcessor is a kafka consumer which
 * provides implementations of the core Content feature extraction operations
 * defined in the IMessageProcessor along with the methods to implement content
 * enrichment with additional metadata
 * 
 * @author Rashmi
 * 
 * @see IMessageProcessor
 */
public class ContentEnrichmentMessageProcessor extends BaseProcessor implements IMessageProcessor {

	private static final String TEMP_FILE_LOCATION = "/data/contentBundle/";

	private static final int AWS_UPLOAD_RESULT_URL_INDEX = 1;

	private static final String s3Content = "s3.content.folder";

	private static final String s3Artifact = "s3.artifact.folder";

	/** The logger. */

	/** The ObjectMapper */
	private static ObjectMapper mapper = new ObjectMapper();

	/** The Controller Utility */
	private ControllerUtil util = new ControllerUtil();

	/** The constructor */
	public ContentEnrichmentMessageProcessor() {
		super();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ekstep.searchindex.processor #processMessage(java.lang.String,
	 * java.lang.String, java.io.File, java.lang.String)
	 */
	@Override
	public void processMessage(String messageData) {
		try {
			Map<String, Object> message = new HashMap<String, Object>();

			if (StringUtils.isNotBlank(messageData)) {
				message = mapper.readValue(messageData, new TypeReference<Map<String, Object>>() {
				});
				if (null != message) {
					String eid = (String) message.get("eid");
					if (StringUtils.isNotBlank(eid) && StringUtils.equals("BE_OBJECT_LIFECYCLE", eid))
						processMessage(message);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ekstep.searchindex.processor #processMessage(java.lang.String
	 * java.lang.String, java.io.File, java.lang.String)
	 */
	@Override
	public void processMessage(Map<String, Object> message) throws Exception {

		Node node = filterMessage(message);
		if (null != node) {
			if (node.getMetadata().get(ContentWorkflowPipelineParams.contentType.name())
					.equals(ContentWorkflowPipelineParams.Collection.name())) {
				PlatformLogger.log("Processing Collection :" + node.getIdentifier(),null, LoggerEnum.INFO.name());
				processCollection(node);
			} else {
				processData(node);
			}
			if (node.getMetadata().get(ContentWorkflowPipelineParams.mimeType.name())
					.equals("application/vnd.ekstep.content-collection")) {
				processCollectionForTOC(node);
			}
		}
	}

	/**
	 * This method holds logic to fetch conceptIds and conceptGrades from the
	 * out relations
	 * 
	 * @param node
	 *            The content node
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void processData(Node node) {

		Set<String> conceptIds = new HashSet<String>();
		Set<String> conceptGrades = new HashSet<String>();
		Map<String, Object> result = new HashMap<String, Object>();

		String graphId = node.getGraphId();
		String contentId = node.getIdentifier();

		if (null != node.getOutRelations() && !node.getOutRelations().isEmpty()) {
			List<Relation> outRelations = node.getOutRelations();
			result = getOutRelationsMap(outRelations);
		}

		if (null != result.get("conceptIds")) {
			List list = (List) result.get("conceptIds");
			if (null != list && !list.isEmpty())
				conceptIds.addAll(list);
		}

		if (null != result.get("conceptGrades")) {
			List list = (List) result.get("conceptGrades");
			if (null != list && !list.isEmpty())
				conceptGrades.addAll(list);
		}

		String language = null;
		if (null != node.getMetadata().get("language")) {
			String[] languageArr = (String[]) node.getMetadata().get("language");
			if (null != languageArr && languageArr.length > 0)
				language = languageArr[0];
		}
		String medium = (String) node.getMetadata().get("medium");
		// setting language as medium if medium is not already set
		if (StringUtils.isBlank(medium) && StringUtils.isNotBlank(language))
			node.getMetadata().put("medium", language);

		String subject = (String) node.getMetadata().get("subject");
		if (StringUtils.isBlank(subject)) {
			// if subject is not set for the content, set the subject using the
			// associated domain
			String domain = (String) result.get("domain");
			if (StringUtils.isNotBlank(domain)) {
				if (StringUtils.equalsIgnoreCase("numeracy", domain))
					subject = "MATHS";
				else if (StringUtils.equalsIgnoreCase("science", domain))
					subject = "Science";
				else if (StringUtils.equalsIgnoreCase("literacy", domain))
					subject = language;
				node.getMetadata().put("subject", subject);
			}
		}

		List<String> items = getItemsMap(node, graphId, contentId);

		PlatformLogger.log("null and empty check for items" + items.isEmpty());
		if (null != items && !items.isEmpty()) {
			PlatformLogger.log("calling getConceptsFromItems method to get concepts from items: " + items.size());
			getConceptsFromItems(graphId, contentId, items, node, conceptIds, conceptGrades);

		} else if (null != conceptGrades && !conceptGrades.isEmpty()) {
			Node content_node = processGrades(node, null, conceptGrades);
			Node contentNode = processAgeGroup(content_node);
			util.updateNode(contentNode);
		}
	}

	@SuppressWarnings("unchecked")
	private void processCollection(Node node) {
		String graphId = node.getGraphId();
		String contentId = node.getIdentifier();
		try {
			Map<String, Object> dataMap = new HashMap<>();
			dataMap = processChildren(node, graphId, dataMap);
			for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
				if ("concepts".equalsIgnoreCase(entry.getKey()) || "keywords".equalsIgnoreCase(entry.getKey())) {
					continue;
				} else if ("subject".equalsIgnoreCase(entry.getKey())) {
					Set<Object> subject = (HashSet<Object>) entry.getValue();
					if (null != subject.iterator().next()) {
						node.getMetadata().put(entry.getKey(), (String) subject.iterator().next());
					}
				} else if ("medium".equalsIgnoreCase(entry.getKey())) {
					Set<Object> medium = (HashSet<Object>) entry.getValue();
					if (null != medium.iterator().next()) {
						node.getMetadata().put(entry.getKey(), (String) medium.iterator().next());
					}
				} else {
					Set<String> valueSet = (HashSet<String>) entry.getValue();
					String[] value = valueSet.toArray(new String[valueSet.size()]);
					node.getMetadata().put(entry.getKey(), value);
					PlatformLogger.log("Updating property" + entry.getKey() + ":" + value);
				}
			}
			Set<String> keywords = (HashSet<String>) dataMap.get("keywords");
			if (null != keywords && !keywords.isEmpty()) {
				if (null != node.getMetadata().get("keywords")) {
					Object object = node.getMetadata().get("keywords");
					if (object instanceof String[]) {
						String[] stringArray = (String[]) node.getMetadata().get("keywords");
						keywords.addAll(Arrays.asList(stringArray));
					} else if (object instanceof String) {
						String keyword = (String) node.getMetadata().get("keywords");
						keywords.add(keyword);
					}
				}
				List<String> keywordsList = new ArrayList<>();
				keywordsList.addAll(keywords);
				node.getMetadata().put("keywords", keywordsList);
			}
			util.updateNode(node);
			List<String> concepts = new ArrayList<>();
			concepts.addAll((Collection<? extends String>) dataMap.get("concepts"));
			if (null != concepts && !concepts.isEmpty()) {
				util.addOutRelations(graphId, contentId, concepts, RelationTypes.ASSOCIATED_TO.relationName());
			}
		} catch (Exception e) {
			PlatformLogger.log("Exception", e.getMessage(), e);
		}
	}

	private Map<String, Object> processChildren(Node node, String graphId, Map<String, Object> dataMap)
			throws Exception {
		List<String> children;
		children = getChildren(node);
		for (String child : children) {
			Node childNode = util.getNode(graphId, child);
			dataMap = mergeMap(dataMap, processChild(childNode));
			processChildren(childNode, graphId, dataMap);
		}
		return dataMap;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> mergeMap(Map<String, Object> dataMap, Map<String, Object> childDataMap)
			throws Exception {
		if (dataMap.isEmpty()) {
			dataMap.putAll(childDataMap);
		} else {
			for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
				Set<Object> value = new HashSet<Object>();
				if (childDataMap.containsKey(entry.getKey())) {
					value.addAll((Collection<? extends Object>) childDataMap.get(entry.getKey()));
				}
				value.addAll((Collection<? extends Object>) entry.getValue());
				dataMap.replace(entry.getKey(), value);
			}
			if (!dataMap.keySet().containsAll(childDataMap.keySet())) {
				for (Map.Entry<String, Object> entry : childDataMap.entrySet()) {
					if (!dataMap.containsKey(entry.getKey())) {
						dataMap.put(entry.getKey(), entry.getValue());
					}
				}
			}
		}
		return dataMap;
	}

	private List<String> getChildren(Node node) throws Exception {
		PlatformLogger.log("In getChildren");
		List<String> children = new ArrayList<>();
		for (Relation rel : node.getOutRelations()) {
			if (ContentWorkflowPipelineParams.Content.name().equalsIgnoreCase(rel.getEndNodeObjectType())) {
				children.add(rel.getEndNodeId());
			}
		}
		return children;
	}

	private Map<String, Object> processChild(Node node) throws Exception {
		PlatformLogger.log("In processChild");
		Map<String, Object> result = new HashMap<>();
		Set<Object> language = new HashSet<Object>();
		Set<Object> concepts = new HashSet<Object>();
		Set<Object> domain = new HashSet<Object>();
		Set<Object> grade = new HashSet<Object>();
		Set<Object> age = new HashSet<Object>();
		Set<Object> medium = new HashSet<Object>();
		Set<Object> subject = new HashSet<Object>();
		Set<Object> genre = new HashSet<Object>();
		Set<Object> theme = new HashSet<Object>();
		Set<Object> keywords = new HashSet<Object>();
		if (null != node.getMetadata().get("language")) {
			String[] langData = (String[]) node.getMetadata().get("language");
			language = new HashSet<Object>(Arrays.asList(langData));
			result.put("language", language);
		}
		if (null != node.getMetadata().get("domain")) {
			String[] domainData = (String[]) node.getMetadata().get("domain");
			domain = new HashSet<Object>(Arrays.asList(domainData));
			result.put("domain", domain);
		}
		if (null != node.getMetadata().get("gradeLevel")) {
			String[] gradeData = (String[]) node.getMetadata().get("gradeLevel");
			grade = new HashSet<Object>(Arrays.asList(gradeData));
			result.put("gradeLevel", grade);
		}
		if (null != node.getMetadata().get("ageGroup")) {
			String[] ageData = (String[]) node.getMetadata().get("ageGroup");
			age = new HashSet<Object>(Arrays.asList(ageData));
			result.put("ageGroup", age);
		}
		if (null != node.getMetadata().get("medium")) {
			String mediumData = (String) node.getMetadata().get("medium");
			medium = new HashSet<Object>(Arrays.asList(mediumData));
			result.put("medium", medium);
		}
		if (null != node.getMetadata().get("subject")) {
			String subjectData = (String) node.getMetadata().get("subject");
			subject = new HashSet<Object>(Arrays.asList(subjectData));
			result.put("subject", subject);
		}
		if (null != node.getMetadata().get("genre")) {
			String[] genreData = (String[]) node.getMetadata().get("genre");
			genre = new HashSet<Object>(Arrays.asList(genreData));
			result.put("genre", genre);
		}
		if (null != node.getMetadata().get("theme")) {
			String[] themeData = (String[]) node.getMetadata().get("theme");
			theme = new HashSet<Object>(Arrays.asList(themeData));
			result.put("theme", theme);
		}
		if (null != node.getMetadata().get("keywords")) {
			String[] keyData = (String[]) node.getMetadata().get("keywords");
			keywords = new HashSet<Object>(Arrays.asList(keyData));
			result.put("keywords", keywords);
		}
		for (Relation rel : node.getOutRelations()) {
			if ("Concept".equalsIgnoreCase(rel.getEndNodeObjectType())) {
				PlatformLogger.log("EndNodeId as Concept ->" + rel.getEndNodeId());
				concepts.add(rel.getEndNodeId());
			}
		}
		if (null != concepts && !concepts.isEmpty()) {
			result.put("concepts", concepts);
		}
		PlatformLogger.log("Concept in resultMap->", result.get("concepts"));
		return result;
	}

	/**
	 * This method gets the list of itemsets associated with content node and
	 * items which are members of item sets used in the content.
	 * 
	 * @param content
	 *            The Content node
	 * 
	 * @param existingConceptGrades
	 *            The conceptGrades from content node
	 * 
	 * @param existingConceptIds
	 *            The existingConceptIds from Content node
	 * 
	 */
	private List<String> getItemsMap(Node node, String graphId, String contentId) {

		Set<String> itemSets = new HashSet<String>();
		List<String> items = new ArrayList<String>();

		try {
			if (null != node.getOutRelations() && !node.getOutRelations().isEmpty()) {
				List<Relation> outRelations = node.getOutRelations();

				PlatformLogger.log("outRelations fetched from each item" + outRelations);
				if (null != outRelations && !outRelations.isEmpty()) {

					PlatformLogger.log("Iterating through relations");
					for (Relation rel : outRelations) {

						PlatformLogger.log("Get item sets associated with the content: " + contentId);
						if (StringUtils.equalsIgnoreCase("ItemSet", rel.getEndNodeObjectType())
								&& !itemSets.contains(rel.getEndNodeId()))
							itemSets.add(rel.getEndNodeId());
					}
				}
			}
			PlatformLogger.log("checking if itemSets are empty" + itemSets);
			if (null != itemSets && !itemSets.isEmpty()) {

				PlatformLogger.log("Number of item sets: " + itemSets.size());
				Set<String> itemIds = new HashSet<String>();

				PlatformLogger.log("Iterating through itemSet map" + itemSets);
				for (String itemSet : itemSets) {

					PlatformLogger.log("calling getItemSetMembers methods to get items from itemSets");
					List<String> members = getItemSetMembers(graphId, itemSet);

					PlatformLogger.log("getting item memebers from item set" + members);
					if (null != members && !members.isEmpty())
						itemIds.addAll(members);
				}
				PlatformLogger.log("Total number of items: " + itemIds.size());
				if (!itemIds.isEmpty()) {
					items = new ArrayList<String>(itemIds);
					PlatformLogger.log("getting items associated with itemsets", items);

				}
			}
		} catch (Exception e) {
			PlatformLogger.log("exception occured while getting item and itemsets", e);
		}
		return items;
	}

	/**
	 * This methods holds logic to get members of givens item set, returns the
	 * list of identifiers of the items that are members of the given item set.
	 * 
	 * @param graphId
	 *            identifier of the domain graph
	 * 
	 * @param itemSetId
	 *            identifier of the item set
	 * 
	 * @return list of identifiers of member items
	 */
	@SuppressWarnings("unchecked")
	private List<String> getItemSetMembers(String graphId, String itemSetId) {

		List<String> members = new ArrayList<String>();
		PlatformLogger.log("Get members of items set: " + itemSetId);
		Response response = util.getCollectionMembers(graphId, itemSetId, CollectionTypes.SET.name());

		PlatformLogger.log("checking if response is null" + response);
		if (null != response) {
			PlatformLogger.log("getting members from response");
			members = (List<String>) response.get(GraphDACParams.members.name());
		}
		PlatformLogger.log("item members fetched from itemSets", members.size());
		return members;
	}

	/**
	 * This method holds logic to map Concepts from the Items, get their
	 * gradeLevel and age Group and add it as a part of node metadata.
	 * 
	 * @param graphId
	 *            The identifier of the domain graph
	 * 
	 * @param items
	 *            The list of assessment item identifiers
	 * 
	 * @param content
	 *            The Content node
	 * 
	 * @param existingConceptIds
	 *            The existingConceptIds from content node
	 * 
	 * @param existingConceptGrades
	 *            grades from concepts associated with content node
	 * 
	 * @return updated node with all metadata
	 * 
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void getConceptsFromItems(String graphId, String contentId, List<String> items, Node content,
			Set<String> existingConceptIds, Set<String> existingConceptGrades) {
		Response response = null;
		Set<String> itemGrades = new HashSet<String>();

		PlatformLogger.log("checking if itemsList is empty" + items);
		if (null != items && !items.isEmpty()) {
			PlatformLogger.log("getting all items Data from itemIds", items);

			response = util.getDataNodes(graphId, items);
			PlatformLogger.log("response from getDataNodes" + response);
		}

		PlatformLogger.log("checking if response is null" + response);
		if (null != response) {

			List<Node> item_nodes = (List<Node>) response.get(GraphDACParams.node_list.name());

			PlatformLogger.log("List of nodes retrieved from response" + item_nodes.size());
			if (null != item_nodes && !item_nodes.isEmpty()) {

				PlatformLogger.log("Iterating through item_nodes");
				for (Node node : item_nodes) {

					PlatformLogger.log("Checking if item node contains gradeLevel");
					if (null != node.getMetadata().get("gradeLevel")) {
						String[] grade_array = (String[]) node.getMetadata().get("gradeLevel");
						for (String grade : grade_array) {
							PlatformLogger.log("adding item grades" + grade);
							itemGrades.add(grade);
						}
					}

					List<Relation> outRelations = node.getOutRelations();
					PlatformLogger.log("calling getOutRelationsMap" + outRelations);
					Map<String, Object> result = getOutRelationsMap(outRelations);

					PlatformLogger.log("fetching conceptIds from result" + result);
					if (null != result.get("conceptIds")) {
						List list = (List) result.get("conceptIds");
						if (null != list && !list.isEmpty())
							existingConceptIds.addAll(list);
					}
				}
			}
		}
		List<String> totalConceptIds = new ArrayList<String>();
		PlatformLogger.log("adding conceptId from content node to list");
		if (null != existingConceptIds && !existingConceptIds.isEmpty()) {
			totalConceptIds.addAll(existingConceptIds);
		}

		PlatformLogger.log("calling process grades method to fetch and update grades");
		Node node = processGrades(content, itemGrades, existingConceptGrades);

		PlatformLogger.log("calling processAgeGroup method to process ageGroups from gradeLevels");
		Node content_node = processAgeGroup(node);

		PlatformLogger.log("updating node with extracted features", content_node.getIdentifier());
		node.setOutRelations(null);
		node.setInRelations(null);
		util.updateNode(content_node);

		if (null != totalConceptIds && !totalConceptIds.isEmpty()) {
			PlatformLogger.log("result node after adding required metadata" + node);
			util.addOutRelations(graphId, contentId, totalConceptIds, RelationTypes.ASSOCIATED_TO.relationName());
		}
	}

	/**
	 * This method mainly holds logic to map the content node with concept
	 * metadata like gradeLevel and ageGroup
	 * 
	 * @param content
	 *            The content node
	 * 
	 * @param itemGrades
	 *            The itemGrades
	 * 
	 * @param conceptGrades
	 *            The conceptGrades
	 * 
	 * @param existingConceptGrades
	 *            The concept grades from content
	 * 
	 * @return updated node with required metadata
	 */
	private Node processGrades(Node node, Set<String> itemGrades, Set<String> existingConceptGrades) {
		Node content_node = null;
		try {

			PlatformLogger.log("checking if concept grades exist" + existingConceptGrades);
			if (null != existingConceptGrades && !existingConceptGrades.isEmpty()) {
				content_node = setGradeLevels(existingConceptGrades, node);
			} else {
				PlatformLogger.log("checking if item grades exist" + itemGrades);
				if (null != itemGrades && !itemGrades.isEmpty()) {
					content_node = setGradeLevels(itemGrades, node);
				}
			}

		} catch (Exception e) {
			PlatformLogger.log("Exception occured while setting age group from grade level", e.getMessage(), e);
		}
		return content_node;
	}

	/**
	 * This method holds logic to getGrades levels either for itemGrades or
	 * conceptGrades and add it to node metadata
	 * 
	 * @param grades
	 *            The grades
	 * 
	 * @param node
	 *            The content node
	 * 
	 * @return The updated content node
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Node setGradeLevels(Set<String> grades, Node node) {

		PlatformLogger.log("checking if node contains gradeLevel");
		if (null == node.getMetadata().get("gradeLevel")) {
			List<String> gradeLevel = new ArrayList(grades);
			node.getMetadata().put("gradeLevel", gradeLevel);

		} else {
			PlatformLogger.log("fetching grade levels from node");
			String[] grade_array = (String[]) node.getMetadata().get("gradeLevel");

			PlatformLogger.log("checking if grade levels obtained are empty ");
			if (null != grade_array) {

				PlatformLogger.log("adding grades which doesnt exist in node" + grades);
				for (String grade : grade_array) {

					PlatformLogger.log("checking if grade already exists" + grade);
					grades.add(grade);
					List gradeLevel = new ArrayList(grades);
					node.getMetadata().put("gradeLevel", gradeLevel);
					PlatformLogger.log("updating node metadata with additional grades" + node);
				}
			}
		}
		return node;
	}

	/**
	 * This method holds logic to map ageGroup from gradeMap
	 * 
	 * @param grades
	 *            The gradeMap
	 * 
	 * @param existingAgeGroup
	 *            The age group from content
	 * 
	 * @return The ageMap mapped from gradeLevel
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Node processAgeGroup(Node node) {
		Node data = null;
		Set<String> ageSet = new HashSet<String>();

		if (null != node.getMetadata().get("gradeLevel")) {
			PlatformLogger.log("fetching gradeLevel from node metadata" + node);
			List<String> grades = (List) node.getMetadata().get("gradeLevel");
			if (null != grades) {

				for (String grade : grades) {
					PlatformLogger.log("mapping age group based on grades");
					if ("Kindergarten".equalsIgnoreCase(grade)) {
						ageSet.add("<5");
					} else if ("Grade 1".equalsIgnoreCase(grade)) {
						ageSet.add("5-6");
					} else if ("Grade 2".equalsIgnoreCase(grade)) {
						ageSet.add("6-7");
					} else if ("Grade 3".equalsIgnoreCase(grade)) {
						ageSet.add("7-8");
					} else if ("Grade 4".equalsIgnoreCase(grade)) {
						ageSet.add("8-10");
					} else if ("Grade 5".equalsIgnoreCase(grade)) {
						ageSet.add(">10");
					} else if ("Other".equalsIgnoreCase(grade)) {
						ageSet.add("Other");
					}
				}
				PlatformLogger.log("Calling set ageGroup method to set ageGroups" + ageSet);
				data = setAgeGroup(node, ageSet);
			}
		}
		return data;
	}

	/**
	 * This method holds logic to set ageGroup based on the grades
	 * 
	 * @param node
	 *            The node
	 * 
	 * @param ageSet
	 *            The ageSet
	 * 
	 * @return The node
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Node setAgeGroup(Node node, Set<String> ageSet) {

		PlatformLogger.log("Checking if node contains ageGroup in it");
		if (null == node.getMetadata().get("ageGroup")) {

			PlatformLogger.log("adding ageSet to node if it doesnt have ageGroup in it");
			if (null != ageSet) {
				PlatformLogger.log("adding age metadata to node" + ageSet);
				List<String> ageGroup = new ArrayList(ageSet);
				node.getMetadata().put("ageGroup", ageGroup);
			}

		} else {

			PlatformLogger.log("fetching ageGroup from node");
			String[] age_array = (String[]) node.getMetadata().get("ageGroup");
			if (null != ageSet) {
				if (null != age_array) {
					for (String age : age_array) {
						ageSet.add(age);
					}
					PlatformLogger.log("adding age metadata to node", ageSet);
					List<String> ageGroup = new ArrayList(ageSet);
					node.getMetadata().put("ageGroup", ageGroup);
				}
			}
		}
		return node;
	}

	@SuppressWarnings("unchecked")
	public void processCollectionForTOC(Node node) {
		try {

			PlatformLogger.log("Processing Collection Content :", node.getIdentifier());
			Response response = util.getHirerachy(node.getIdentifier());
			if (null != response && null != response.getResult()) {
				Map<String, Object> content = (Map<String, Object>) response.getResult().get("content");
				Map<String, Object> mimeTypeMap = new HashMap<>();
				Map<String, Object> contentTypeMap = new HashMap<>();
				int leafCount = 0;
				getTypeCount(content, "mimeType", mimeTypeMap);
				getTypeCount(content, "contentType", contentTypeMap);
				content.put(ContentAPIParams.mimeTypesCount.name(), mimeTypeMap);
				content.put(ContentAPIParams.contentTypesCount.name(), contentTypeMap);
				leafCount = getLeafNodeCount(content, leafCount);
				content.put(ContentAPIParams.leafNodesCount.name(), leafCount);
				PlatformLogger.log("Write hirerachy to JSON File :" + node.getIdentifier());
				String data = mapper.writeValueAsString(content);
				File file = new File(getBasePath(node.getIdentifier()) + "TOC.json");
				try {
					FileUtils.writeStringToFile(file, data);
					if (file.exists()) {
						PlatformLogger.log("Upload File to S3 :", file.getName());
						String[] uploadedFileUrl = AWSUploader.uploadFile(getAWSPath(node.getIdentifier()), file);
						if (null != uploadedFileUrl && uploadedFileUrl.length > 1) {
							String url = uploadedFileUrl[AWS_UPLOAD_RESULT_URL_INDEX];
							PlatformLogger.log("Update S3 url to node" + url);
							node.getMetadata().put(ContentAPIParams.toc_url.name(), url);
						}
						FileUtils.deleteDirectory(file.getParentFile());
						PlatformLogger.log("Deleting Uploaded files");
					}
				} catch (Exception e) {
					PlatformLogger.log("Error while uploading file " + e);
				}
				node.getMetadata().put(ContentAPIParams.mimeTypesCount.name(), mimeTypeMap);
				node.getMetadata().put(ContentAPIParams.contentTypesCount.name(), contentTypeMap);
				node.getMetadata().put(ContentAPIParams.leafNodesCount.name(), leafCount);
				util.updateNode(node);
				PlatformLogger.log("Updating Node MetaData");
			}
		} catch (Exception e) {
			PlatformLogger.log("Error while processing the collection ", e.getMessage(), e);
		}

	}

	@SuppressWarnings("unchecked")
	private void getTypeCount(Map<String, Object> data, String type, Map<String, Object> typeMap) {
		List<Object> children = (List<Object>) data.get("children");
		if (null != children && !children.isEmpty()) {
			for (Object child : children) {
				Map<String, Object> childMap = (Map<String, Object>) child;
				String typeValue = childMap.get(type).toString();
				if (typeMap.containsKey(typeValue)) {
					int count = (int) typeMap.get(typeValue);
					count++;
					typeMap.put(typeValue, count);
				} else {
					typeMap.put(typeValue, 1);
				}
				if (childMap.containsKey("children")) {
					getTypeCount(childMap, type, typeMap);
				}
			}
		}

	}

	@SuppressWarnings("unchecked")
	private Integer getLeafNodeCount(Map<String, Object> data, int leafCount) {
		List<Object> children = (List<Object>) data.get("children");
		if (null != children && !children.isEmpty()) {
			for (Object child : children) {
				Map<String, Object> childMap = (Map<String, Object>) child;
				int lc = 0;
				lc = getLeafNodeCount(childMap, lc);
				leafCount = leafCount + lc;
			}
		} else {
			leafCount++;
		}
		return leafCount;
	}

	private String getBasePath(String contentId) {
		String path = "";
		if (!StringUtils.isBlank(contentId))
			path = TEMP_FILE_LOCATION + File.separator + System.currentTimeMillis() + ContentAPIParams._temp.name()
					+ File.separator + contentId;
		return path;
	}

	private String getAWSPath(String identifier) {
		String folderName = S3PropertyReader.getProperty(s3Content);
		if (!StringUtils.isBlank(folderName)) {
			folderName = folderName + File.separator + Slug.makeSlug(identifier, true) + File.separator
					+ S3PropertyReader.getProperty(s3Artifact);
		}
		return folderName;
	}

}