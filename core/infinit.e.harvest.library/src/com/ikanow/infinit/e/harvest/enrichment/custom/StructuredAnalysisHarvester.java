/*******************************************************************************
 * Copyright 2012, The Infinit.e Open Source Project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package com.ikanow.infinit.e.harvest.enrichment.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.log4j.Logger;
import org.bson.types.ObjectId;
import org.json.JSONException;

import au.com.bytecode.opencsv.CSVParser;

import com.google.gson.GsonBuilder;
import com.ikanow.infinit.e.data_model.Globals;
import com.ikanow.infinit.e.data_model.store.DbManager;
import com.ikanow.infinit.e.data_model.store.MongoDbUtil;
import com.ikanow.infinit.e.data_model.store.config.source.SourcePipelinePojo.DocumentSpecPojo;
import com.ikanow.infinit.e.data_model.store.config.source.SourcePojo;
import com.ikanow.infinit.e.data_model.store.config.source.StructuredAnalysisConfigPojo;
import com.ikanow.infinit.e.data_model.store.config.source.StructuredAnalysisConfigPojo.AssociationSpecPojo;
import com.ikanow.infinit.e.data_model.store.config.source.StructuredAnalysisConfigPojo.EntitySpecPojo;
import com.ikanow.infinit.e.data_model.store.config.source.StructuredAnalysisConfigPojo.GeoSpecPojo;
import com.ikanow.infinit.e.data_model.store.document.AssociationPojo;
import com.ikanow.infinit.e.data_model.store.document.DocumentPojo;
import com.ikanow.infinit.e.data_model.store.document.EntityPojo;
import com.ikanow.infinit.e.data_model.store.document.GeoPojo;
import com.ikanow.infinit.e.data_model.store.feature.geo.GeoFeaturePojo;
import com.ikanow.infinit.e.data_model.utils.DimensionUtility;
import com.ikanow.infinit.e.data_model.utils.GeoOntologyMapping;
import com.ikanow.infinit.e.harvest.HarvestContext;
import com.ikanow.infinit.e.harvest.HarvestController;
import com.ikanow.infinit.e.harvest.enrichment.script.CompiledScriptFactory;
import com.ikanow.infinit.e.harvest.enrichment.script.CompiledScriptWrapperUtility;
import com.ikanow.infinit.e.harvest.utils.AssociationUtils;
import com.ikanow.infinit.e.harvest.utils.DateUtility;
import com.ikanow.infinit.e.harvest.utils.HarvestExceptionUtils;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

/**
 * StructuredAnalysisHarvester
 * @author cvitter
 */
public class StructuredAnalysisHarvester 
{
	///////////////////////////////////////////////////////////////////////////////////////////
	private CompiledScriptFactory compiledScriptFactory = null;
	

	// NEW PROCESSING PIPELINE INTERFACE
	
	public void setContext(HarvestContext context) {
		_context = context;
		// Setup some globals if necessary
	}
	
	public void resetForNewDoc() {
		resetEntityCache();
		resetDocumentCache();
	}
	
	public void resetEntityCache() {
		// Clear geoMap before we start extracting entities and associations for each feed
		if (null != _entityMap) {
			if (!_geoMap.isEmpty()) _geoMap.clear();
			if (!_entityMap.isEmpty()) _entityMap.clear();
			// Fill in geoMap and entityMap with any existing docs/entities
			_entityMap = null;
			_geoMap = null;
		}
	}//TESTED (entity_cache_reset_test)
	
	public void resetDocumentCache() {
		this._docPojo = null;
	}
	
	// Load global functions
	// (scriptLang currently ignored)
	
	public void loadGlobalFunctions(List<String> imports, List<String> scripts, String scriptLang) 
	{
		intializeScriptEngine(compiledScriptFactory);
        // Pass scripts into the engine
	}//TESTED (uah:import_and_lookup_test_uahSah.json)
	
	// Set the document level fields
	
	public void setDocumentMetadata(DocumentPojo doc, DocumentSpecPojo docMetadataConfig) throws JSONException, ScriptException {
		intializeDocIfNeeded(doc);
		
		// We'll just basically duplicate the code from executeHarvest() since it's pretty simple
		// and it isn't very easy to pull out the logic in there (which is unnecessarily complicated for
		// the pipeline version since you don't need to work out whether to generate the fields before or
		// after the other stages, you get to explicity specify)
		
		// Extract Title if applicable
		try {
			if (docMetadataConfig.title != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.title)) {
					doc.setTitle((String)getValueFromScript(docMetadataConfig.title, null, null));
				}
				else {
					doc.setTitle(FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.title, null));
				}
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("title: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("title: " + e.getMessage(), e);
		}
		//TESTED (fulltext_docMetaTest)

		// Extract display URL if applicable
		try {
			if (docMetadataConfig.displayUrl != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.displayUrl)) {
					doc.setDisplayUrl((String)getValueFromScript(docMetadataConfig.displayUrl, null, null));
				}
				else {
					doc.setDisplayUrl(FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.displayUrl, null));
				}
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("displayUrl: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("displayUrl: " + e.getMessage(), e);
		}
		//TESTED (fulltext_docMetaTest)

		// Extract mediaType if applicable
		try {
			if (docMetadataConfig.mediaType != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.mediaType)) {
					doc.setMediaType((String)getValueFromScript(docMetadataConfig.mediaType, null, null));
				}
				else {
					doc.setMediaType(FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.mediaType, null));
				}
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("mediaType: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("mediaType: " + e.getMessage(), e);
		}
		//TESTED (basic_docMeta_type_tags_test)

		// Extract tags if applicable
		try {
			if ((docMetadataConfig.tags != null) && !docMetadataConfig.tags.isEmpty()) {
				String tagVals = null;
				if (JavaScriptUtils.containsScript(docMetadataConfig.tags)) {
					tagVals = (String)getValueFromScript(docMetadataConfig.tags, null, null);
				}
				else {
					tagVals = FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.tags, null);
				}
				CSVParser csv = new CSVParser();
				String tags[] = csv.parseLine(tagVals);
				for (String tag: tags) {
					if (tag.equalsIgnoreCase("null")) { 
						continue;
					}
					if ((null != tag) && !tag.isEmpty()) {
						if (null == doc.getTags()) {
							doc.setTags(new HashSet<String>());
						}
						if ('-' == tag.charAt(0)) {
							doc.getTags().remove(tag.substring(1));
						}
						else {
							doc.getTags().add(tag);
						}
					}
				}
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("tags: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("displayUrl: " + e.getMessage(), e);
		}
		//TESTED (basic_docMeta_type_tags_test)
		
		// Extract Description if applicable
		try {
			if (docMetadataConfig.description != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.description)) {
					doc.setDescription((String)getValueFromScript(docMetadataConfig.description, null, null));
				}
				else {
					doc.setDescription(FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.description, null));
				}
			}
		}
		catch (Exception e)  {
			this._context.getHarvestStatus().logMessage("description: " + e.getMessage(), true);						
			//DEBUG (don't output log messages per doc)
			//logger.error("description: " + e.getMessage(), e);
		}
		//TESTED (fulltext_docMetaTest)
		

		// Extract fullText if applicable
		try {
			if (docMetadataConfig.fullText != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.fullText)) {
					doc.setFullText((String)getValueFromScript(docMetadataConfig.fullText, null, null));
				}
				else {
					doc.setFullText(FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.fullText, null));
				}
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("fullText: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("fullText: " + e.getMessage(), e);
		}
		//TESTED (fulltext_docMetaTest)

		// Extract Published Date if applicable
		try {
			if (docMetadataConfig.publishedDate != null) {
				if (JavaScriptUtils.containsScript(docMetadataConfig.publishedDate)) {
						doc.setPublishedDate(new Date(
								DateUtility.parseDate((String)getValueFromScript(docMetadataConfig.publishedDate, null, null))));
				}
				else {
					doc.setPublishedDate(new Date(
							DateUtility.parseDate((String)FieldExtractor.getFormattedTextFromField(_docPojo,docMetadataConfig.publishedDate, null))));
				} 
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("publishedDate: " + e.getMessage(), true);
			//DEBUG (don't output log messages per doc)
			//logger.error("publishedDate: " + e.getMessage()+" for document:"+_gson.toJson(_docPojo), e);
		}
		//TESTED (fulltext_docMetaTest)
		
		// Extract Document GEO if applicable
		
		try {
			if (docMetadataConfig.geotag != null) {
				doc.setDocGeo(getDocGeo(docMetadataConfig.geotag));
			}
		}
		catch (Exception e) {
			this._context.getHarvestStatus().logMessage("docGeo: " + e.getMessage(), true);						
			//DEBUG (don't output log messages per doc)
			//logger.error("docGeo: " + e.getMessage(), e);
		}
		//TESTED (fulltext_docMetaTest)
	}
	//TESTED (fulltext_docMetaTest)
	
	// Set the entities
	
	StructuredAnalysisConfigPojo _pipelineTmpConfig = null;
	
	public void setEntities(DocumentPojo doc, List<EntitySpecPojo> entSpecs) throws JSONException, ScriptException {
		intializeDocIfNeeded(doc);
		if (null == _pipelineTmpConfig) {
			_pipelineTmpConfig = new StructuredAnalysisConfigPojo();
		}
		_pipelineTmpConfig.setEntities(entSpecs);
		expandIterationLoops(_pipelineTmpConfig);
		List<EntityPojo> ents = getEntities(_pipelineTmpConfig.getEntities(), doc);
		if (null == doc.getEntities()) { // (else has already been added by getEntities)
			doc.setEntities(ents);
		}
	}
	//TESTED (both first time through, and when adding to existing entities)
	
	// Set the associations
	
	public void setAssociations(DocumentPojo doc, List<AssociationSpecPojo> assocSpecs) throws JSONException, ScriptException {
		
		//TODO (INF-1922): Allow setting of directed sentiment (here and in legacy code)
		
		intializeDocIfNeeded(doc);
		if (null == _pipelineTmpConfig) {
			_pipelineTmpConfig = new StructuredAnalysisConfigPojo();
		}
		_pipelineTmpConfig.setAssociations(assocSpecs);
		expandIterationLoops(_pipelineTmpConfig);
		List<AssociationPojo> assocs = getAssociations(_pipelineTmpConfig.getAssociations(), doc);
		if (null == doc.getAssociations()) { // (else has already been added by getAssociations)
			doc.setAssociations(assocs);
		}
	}
	//TESTED (both first time through, and when adding to existing associations)
	
	///////////////////////////////////////////////////////////////////////////////////////////
	
	// (Utility function for optimization)
	protected void intializeDocIfNeeded(DocumentPojo f) throws JSONException, ScriptException {
		_docPojo = f;
		CompiledScriptWrapperUtility.initializeDocumentPojoInEngine(compiledScriptFactory,f);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	
	// Loads the caches into script
	
	public void loadLookupCaches(Map<String, ObjectId> caches, Set<ObjectId> communityIds, ObjectId sourceOwnerId) {
		//grab any json cache and make it available to the engine
		try
		{
			if (null != caches) {
				//CacheUtils.addJSONCachesToEngineCompiled(caches, compiledScriptFactory, communityIds, _context);
				List<String> errs = CacheUtils.addJSONCachesToEngine(caches, compiledScriptFactory, communityIds, sourceOwnerId, _context);
				for (String err: errs) {
					_context.getHarvestStatus().logMessage(err, true);
				}
			}
		}
		catch (Exception ex)
		{
			StringBuffer sb = new StringBuffer("JSONcache: ").append(ex.getMessage());
			Globals.populateStackTrace(sb, ex);
			_context.getHarvestStatus().logMessage(sb.toString(), true);
			//(no need to log this, appears in log under source -with URL- anyway):
			//logger.error(sb.toString());
		}		
	}//TESTED (import_and_lookup_test_uahSah.json)
	
	///////////////////////////////////////////////////////////////////////////////////////////

	// Tidy up metadadata after processing
	
	public void removeUnwantedMetadataFields(String metaFields, DocumentPojo f)
	{
		if (null != f.getMetadata()) {
			if (null != metaFields) {
				boolean bInclude = true;
				if (metaFields.startsWith("+")) {
					metaFields = metaFields.substring(1);
				}
				else if (metaFields.startsWith("-")) {
					metaFields = metaFields.substring(1);
					bInclude = false;
				}
				String[] metaFieldArray = metaFields.split("\\s*,\\s*");
				if (bInclude) {
					Set<String> metaFieldSet = new HashSet<String>();
					metaFieldSet.addAll(Arrays.asList(metaFieldArray));
					Iterator<Entry<String,  Object[]>> metaField = f.getMetadata().entrySet().iterator();
					while (metaField.hasNext()) {
						Entry<String,  Object[]> metaFieldIt = metaField.next();
						if (!metaFieldSet.contains(metaFieldIt.getKey())) {
							metaField.remove();
						}
					}
				} 
				else { // exclude case, easier
					for (String metaField: metaFieldArray) {
						if (!metaField.contains(".")) {
							f.getMetadata().remove(metaField);
						}
						else { // more complex case, nested delete							
							MongoDbUtil.recursiveNestedMapDelete(metaField.split("\\s*\\.\\s*"), 0, f.getMetadata());						
						}
					}//(end loop over metaFields)
					
				}//(end if exclude case)
				//TESTED: include (default + explicit) and exclude cases
			}
		}//(if metadata exists)		
	}//TESTED (legacy code)
	
	public boolean rejectDoc(String rejectDocCriteria, DocumentPojo f) throws JSONException, ScriptException
	{
		return rejectDoc(rejectDocCriteria, f, true);
	}
	public boolean rejectDoc(String rejectDocCriteria, DocumentPojo f, boolean logMessage) throws JSONException, ScriptException
	{
		if (null != rejectDocCriteria) {			
			intializeDocIfNeeded(f);			
			
			Object o = getValueFromScript(rejectDocCriteria, null, null, false);
			if (null != o) {
				if (o instanceof String) {
					String rejectDoc = (String)o;
					if (null != rejectDoc) {
						if (logMessage) {
							this._context.getHarvestStatus().logMessage("SAH_reject: " + rejectDoc, true);
						}
						return true;
					}					
				}
				else if (o instanceof Boolean) {
					Boolean rejectDoc = (Boolean)o;
					if (rejectDoc) {
						if (logMessage) {
							this._context.getHarvestStatus().logMessage("SAH_reject: reason not specified", true);
						}
						return true;
					}					
				}
				else {
					if (logMessage) {
						this._context.getHarvestStatus().logMessage("SAH_reject: reason not specified", true);
					}
					return true;					
				}
			}
		}
		return false;
	}//TESTED (storageSettings_test + legacy code)
	
	public void handleDocumentUpdates(String onUpdateScript, DocumentPojo f) throws JSONException, ScriptException
	{
		// Compare the new and old docs in the case when this doc is an update
		if ((null != onUpdateScript) && (null != f.getUpdateId())) {
			// (note we must be in integrated mode - not called from source/test - if f.getId() != null)
			intializeDocIfNeeded(f);			
			
			BasicDBObject query1 = new BasicDBObject(DocumentPojo._id_, f.getUpdateId());
			BasicDBObject query2 = new BasicDBObject(DocumentPojo.updateId_, f.getUpdateId());
			BasicDBObject query = new BasicDBObject(DbManager.or_, Arrays.asList(query1, query2));

			BasicDBObject docObj = (BasicDBObject) DbManager.getDocument().getMetadata().findOne(query);
			
			if (null != docObj) {
				
				DocumentPojo oldDoc = DocumentPojo.fromDb(docObj, DocumentPojo.class);
		        try {
					// COMPILED_SCRIPT
		        	compiledScriptFactory.executeCompiledScript(JavaScriptUtils.initOnUpdateScript,"old_document",oldDoc);
		        	Object returnVal = compiledScriptFactory.executeCompiledScript(onUpdateScript);
					BasicDBList outList = JavaScriptUtils.parseNativeJsObjectCompiled(returnVal, compiledScriptFactory);												
					f.addToMetadata("_PERSISTENT_", outList.toArray());		        	
		        }
		        catch (Exception e) {
		        	// Extra step here...
		        	if (null != oldDoc.getMetadata()) { // Copy persistent metadata across...
		        		Object[] persist = oldDoc.getMetadata().get("_PERSISTENT_");
		        		if (null != persist) {
		        			f.addToMetadata("_PERSISTENT_", persist);
		        		}						        		
						this._context.getHarvestStatus().logMessage("SAH::onUpdateScript: " + e.getMessage(), true);
						//DEBUG (don't output log messages per doc)
						//logger.error("SAH::onUpdateScript: " + e.getMessage(), e);
		        	}
		        	//(TESTED)
		        }								
				//TODO (INF-1507): need to write more efficient code to deserialize metadata?
			}
		}//TESTED (end if callback-on-update)
	}//TESTED (legacy code)
	
	///////////////////////////////////////////////////////////////////////////////////////////
	
	// PROCESSING PIPELINE - UTILITIES
	
	// Intialize script engine - currently only Java script is supported
	
	public void intializeScriptEngine(CompiledScriptFactory compiledScriptFactory)
	{
		if(this.compiledScriptFactory==null){
			this.compiledScriptFactory = compiledScriptFactory;
	        try {
	        	// COMPILED_SCRIPT initialization 
	            this.compiledScriptFactory.executeCompiledScript(CompiledScriptFactory.GLOBAL);
			} 
	        catch (Exception e) {
				this._context.getHarvestStatus().logMessage("ScriptException (globals): " + e.getMessage(), true);
				//DEBUG
				//logger.error("ScriptException (globals): " + e.getMessage(), e);
			}			
		}			
	}//TESTED
	
	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////
	
	// LEGACY CODE - USE TO SUPPORT OLD CODE FOR NOW + AS UTILITY CODE FOR THE PIPELINE LOGIC
	
	// Private class variables
	private static Logger logger;
    DocumentPojo _docPojo = null;
	private Object _iterator = null;
	private String _iteratorIndex = null;
	private HashMap<String, GeoPojo> _geoMap = null;
	private HashSet<String> _entityMap = null;
	private HashSet<String> _entitiesToDeleteMap = null; // has persistence of addEntities)
	private HashSet<String> _assocsToDeleteMap = null; // has persistence of addAssociations)
	
	private HarvestContext _context;
	
	/**
	 * Default Constructor
	 */
	public StructuredAnalysisHarvester()
	{			
		logger = Logger.getLogger(StructuredAnalysisHarvester.class);
	}
	
	// Allows the unstructured handler to take advantage of text created by this
	public void addUnstructuredHandler(UnstructuredAnalysisHarvester uap) {
		_unstructuredHandler = uap;
	}
	private UnstructuredAnalysisHarvester _unstructuredHandler = null;
	
	/**
	 * executeHarvest(SourcePojo source, List<DocumentPojo> feeds) extracts document GEO, Entities,
	 * and Associations based on the DocGeoSpec, EntitySpec, and AssociationSpec information contained
	 * within the source document's StructuredAnalysis sections
	 * @param source
	 * @param docs
	 * @return List<DocumentPojo>
	 * @throws ScriptException 
	 */
	public List<DocumentPojo> executeHarvest(HarvestController contextController, SourcePojo source, List<DocumentPojo> docs)
	{
		_context = contextController;
		
		// Skip if the StructuredAnalysis object of the source is null 
		if (source.getStructuredAnalysisConfig() != null)
		{
			StructuredAnalysisConfigPojo s = source.getStructuredAnalysisConfig();			
			// (some pre-processing to expand the specs)
			expandIterationLoops(s);	
			
			// Instantiate a new ScriptEngineManager and create an engine to execute  
			// the type of script specified in StructuredAnalysisPojo.scriptEngine
			this.intializeScriptEngine(compiledScriptFactory);			
						
			this.loadLookupCaches(s.getCaches(), source.getCommunityIds(), source.getOwnerId());			

			// Iterate over each doc in docs, create entity and association pojo objects
			// to add to the feed using the source entity and association spec pojos
			Iterator<DocumentPojo> it = docs.iterator();
			int nDocs = 0;
			while (it.hasNext())
			{
				DocumentPojo f = it.next();
				nDocs++;
				try 
				{ 
					resetEntityCache();					
						// (don't create this until needed, since it might need to be (re)serialized after a call
						//  to the UAH which would obviously be undesirable)
								        					
					// If the script engine has been instantiated pass the feed document and any scripts
					if (compiledScriptFactory != null)
					{
						List<String> scriptList = null;
						List<String> scriptFileList = null;
						try {
							// Script code embedded in source
							scriptList = Arrays.asList(s.getScript());
						}
						catch (Exception e) {}
						try {
							// scriptFiles - can contain String[] of script files to import into the engine
							scriptFileList = Arrays.asList(s.getScriptFiles());							
						}
						catch (Exception e) {}							
						this.loadGlobalFunctions(scriptFileList, scriptList, s.getScriptEngine());						
					}//TESTED
					
			// 1. Document level fields
					
					// Extract Title if applicable
					boolean bTryTitleLater = false;
					try {
						if (s.getTitle() != null)
						{
							intializeDocIfNeeded(f);
							if (JavaScriptUtils.containsScript(s.getTitle()))
							{
								f.setTitle((String)getValueFromScript(s.getTitle(), null, null));
							}
							else
							{
								f.setTitle(FieldExtractor.getFormattedTextFromField(_docPojo,s.getTitle(), null));
							}
							if (null == f.getTitle()) {
								bTryTitleLater = true;
							}
						}
					}
					catch (Exception e) 
					{
						this._context.getHarvestStatus().logMessage("title: " + e.getMessage(), true);						
						//DEBUG (don't output log messages per doc)
						//logger.error("title: " + e.getMessage(), e);
					}

					// Extract Display URL if applicable
					boolean bTryDisplayUrlLater = false;
					try {
						if (s.getDisplayUrl() != null)
						{
							intializeDocIfNeeded(f);
							if (JavaScriptUtils.containsScript(s.getDisplayUrl()))
							{
								f.setDisplayUrl((String)getValueFromScript(s.getDisplayUrl(), null, null));
							}
							else
							{
								f.setDisplayUrl(FieldExtractor.getFormattedTextFromField(_docPojo,s.getDisplayUrl(), null));
							}
							if (null == f.getDisplayUrl()) {
								bTryDisplayUrlLater = true;
							}
						}
					}
					catch (Exception e) 
					{
						this._context.getHarvestStatus().logMessage("displayUrl: " + e.getMessage(), true);						
						//DEBUG (don't output log messages per doc)
						//logger.error("displayUrl: " + e.getMessage(), e);
					}
					//TOTEST

					// Extract Description if applicable
					boolean bTryDescriptionLater = false;
					try {
						if (s.getDescription() != null)
						{
							intializeDocIfNeeded(f);
							if (JavaScriptUtils.containsScript(s.getDescription()))
							{
								f.setDescription((String)getValueFromScript(s.getDescription(), null, null));
							}
							else
							{
								f.setDescription(FieldExtractor.getFormattedTextFromField(_docPojo,s.getDescription(), null));
							}
							if (null == f.getDescription()) {
								bTryDescriptionLater = true;
							}
						}
					}
					catch (Exception e) 
					{
						this._context.getHarvestStatus().logMessage("description: " + e.getMessage(), true);						
						//DEBUG (don't output log messages per doc)
						//logger.error("description: " + e.getMessage(), e);
					}
					

					// Extract fullText if applicable
					boolean bTryFullTextLater = false;
					try {
						if (s.getFullText() != null)
						{
							intializeDocIfNeeded(f);
							if (JavaScriptUtils.containsScript(s.getFullText()))
							{
								f.setFullText((String)getValueFromScript(s.getFullText(), null, null));
							}
							else
							{
								f.setFullText(FieldExtractor.getFormattedTextFromField(_docPojo,s.getFullText(), null));
							}
							if (null == f.getFullText()) {
								bTryFullTextLater = true;
							}
						}
					}
					catch (Exception e) 
					{
						this._context.getHarvestStatus().logMessage("fullText: " + e.getMessage(), true);						
						//DEBUG (don't output log messages per doc)
						//logger.error("fullText: " + e.getMessage(), e);
					}
	
					// Published date is done after the UAH 
					// (since the UAH can't access it, and it might be populated via the UAH)
					
			// 2. UAH/extraction properties
					
					// Add fields to metadata that can be used to create entities and associations
					// (Either with the UAH, or with the entity extractor)
					try {
						boolean bMetadataChanged = false;
						if (null != this._unstructuredHandler) 
						{
							try 
							{
								bMetadataChanged = this._unstructuredHandler.executeHarvest(_context, source, f, (1 == nDocs), it.hasNext());
							}
							catch (Exception e) {
								contextController.handleExtractError(e, source); //handle extractor error if need be		
								
								it.remove(); // remove the document from the list...
								f.setTempSource(null); // (can safely corrupt this doc since it's been removed)
								
								// (Note: this can't be source level error, so carry on harvesting - unlike below)
								continue;
							}
						}	
						if (contextController.isEntityExtractionRequired(source))
						{
							bMetadataChanged = true;
							
							// Text/Entity Extraction 
							List<DocumentPojo> toAdd = new ArrayList<DocumentPojo>(1);
							toAdd.add(f);
							try {
								contextController.extractTextAndEntities(toAdd, source, false, false);
								if (toAdd.isEmpty()) { // this failed... 
									it.remove(); // remove the document from the list...
									f.setTempSource(null); // (can safely corrupt this doc since it's been removed)
									continue;
								}//TESTED
							}
							catch (Exception e) {
								contextController.handleExtractError(e, source); //handle extractor error if need be				
								it.remove(); // remove the document from the list...
								f.setTempSource(null); // (can safely corrupt this doc since it's been removed)
								
								if (source.isHarvestBadSource())
								{
									// Source error, ignore all other documents
									while (it.hasNext()) {
										f = it.next();
										f.setTempSource(null); // (can safely corrupt this doc since it's been removed)
										it.remove();
									}
									break;
								}
								else {
									continue;
								}
								//TESTED
							}
						}
						if (bMetadataChanged) {
							// Ugly, but need to re-create doc json because metadata has changed
							String sTmpFullText = f.getFullText();
							f.setFullText(null); // (no need to serialize this, can save some cycles)
							intializeDocIfNeeded(f);							
					        f.setFullText(sTmpFullText); //(restore)
						}
						
						// Can copy metadata from old documents to new ones:						
						handleDocumentUpdates(s.getOnUpdateScript(), f);
						
						// Check (based on the metadata and entities so far) whether to retain the doc
						if (rejectDoc(s.getRejectDocCriteria(), f)) {
							it.remove(); // remove the document from the list...
							f.setTempSource(null); // (can safely corrupt this doc since it's been removed)
							continue;															
						}
					}
					catch (Exception e) {
						this._context.getHarvestStatus().logMessage("SAH->UAH: " + e.getMessage(), true);						
						//DEBUG (don't output log messages per doc)
						//logger.error("SAH->UAH: " + e.getMessage(), e);
					}
						
					// Now create document since there's no risk of having to re-serialize
					intializeDocIfNeeded(f);
					
			// 3. final doc-level metadata fields:
					
					// If description was null before might need to get it from a UAH field
					if (bTryTitleLater) {
						try {
							if (s.getTitle() != null)
							{
								intializeDocIfNeeded(f);
								if (JavaScriptUtils.containsScript(s.getTitle()))
								{
									f.setTitle((String)getValueFromScript(s.getTitle(), null, null));
								}
								else
								{
									f.setTitle(FieldExtractor.getFormattedTextFromField(_docPojo,s.getTitle(), null));
								}
							}
						}
						catch (Exception e) 
						{
							this._context.getHarvestStatus().logMessage("title: " + e.getMessage(), true);						
							//DEBUG (don't output log messages per doc)
							//logger.error("title: " + e.getMessage(), e);
						}
					}
					
					// Extract Display URL if needed
					if (bTryDisplayUrlLater) {
						try {
							if (s.getDisplayUrl() != null)
							{
								intializeDocIfNeeded(f);
								if (JavaScriptUtils.containsScript(s.getDisplayUrl()))
								{
									f.setDisplayUrl((String)getValueFromScript(s.getDisplayUrl(), null, null));
								}
								else
								{
									f.setDisplayUrl(FieldExtractor.getFormattedTextFromField(_docPojo,s.getDisplayUrl(), null));
								}
							}
						}
						catch (Exception e) 
						{
							this._context.getHarvestStatus().logMessage("displayUrl: " + e.getMessage(), true);						
							//DEBUG (don't output log messages per doc)
							//logger.error("displayUrl: " + e.getMessage(), e);
						}
					}					
					//TOTEST
					
					// If description was null before might need to get it from a UAH field
					if (bTryDescriptionLater) {
						try {
							if (s.getDescription() != null)
							{
								intializeDocIfNeeded(f);
								if (JavaScriptUtils.containsScript(s.getDescription()))
								{
									f.setDescription((String)getValueFromScript(s.getDescription(), null, null));
								}
								else
								{
									f.setDescription(FieldExtractor.getFormattedTextFromField(_docPojo,s.getDescription(), null));
								}
							}
						}
						catch (Exception e) 
						{
							this._context.getHarvestStatus().logMessage("description2: " + e.getMessage(), true);						
							//DEBUG (don't output log messages per doc)
							//logger.error("description2: " + e.getMessage(), e);
						}						
					}
					
					// If fullText was null before might need to get it from a UAH field
					if (bTryFullTextLater) {
						try {
							if (s.getFullText() != null)
							{
								intializeDocIfNeeded(f);
								if (JavaScriptUtils.containsScript(s.getFullText()))
								{
									f.setFullText((String)getValueFromScript(s.getFullText(), null, null));
								}
								else
								{
									f.setFullText(FieldExtractor.getFormattedTextFromField(_docPojo,s.getFullText(), null));
								}
							}
						}
						catch (Exception e) 
						{
							this._context.getHarvestStatus().logMessage("fullText2: " + e.getMessage(), true);						
							//DEBUG (don't output log messages per doc)
							//logger.error("fullText2: " + e.getMessage(), e);
						}						
					}
					
					// Extract Published Date if applicable
					if (s.getPublishedDate() != null)
					{
						if (JavaScriptUtils.containsScript(s.getPublishedDate()))
						{
							try 
							{
								f.setPublishedDate(new Date(
										DateUtility.parseDate((String)getValueFromScript(s.getPublishedDate(), null, null))));
							}
							catch (Exception e) 
							{
								this._context.getHarvestStatus().logMessage("publishedDate: " + e.getMessage(), true);						
							}
						}
						else
						{
							try 
							{ 
								f.setPublishedDate(new Date(
										DateUtility.parseDate((String)FieldExtractor.getFormattedTextFromField(_docPojo,s.getPublishedDate(), null))));
							} 
							catch (Exception e) 
							{
								this._context.getHarvestStatus().logMessage("publishedDate: " + e.getMessage(), true);						
							}
						}
					}
					
			// 4. Entity level fields		
					
					// Extract Document GEO if applicable
					
					if (s.getDocumentGeo() != null)
					{
						try
						{
							f.setDocGeo(getDocGeo(s.getDocumentGeo()));
						}
						catch (Exception e)
						{
							this._context.getHarvestStatus().logMessage("docGeo: " + e.getMessage(), true);						
						}
					}

					// Extract Entities
					if (s.getEntities() != null)
					{
						f.setEntities(getEntities(s.getEntities(), f));
					}

					// Extract Associations
					if (s.getAssociations() != null)
					{
						f.setAssociations(getAssociations(s.getAssociations(), f));
					}
					
			// 5. Remove unwanted metadata fields
					
					removeUnwantedMetadataFields(s.getMetadataFields(), f);					
				} 
				catch (Exception e) 
				{
					this._context.getHarvestStatus().logMessage("Unknown: " + e.getMessage(), true);						
					//DEBUG (don't output log messages per doc)
					//logger.error("Unknown: " + e.getMessage(), e);
				}				
			} // (end loop over documents)
		} // (end if SAH specified)	
		return docs;
	}

	/**
	 * getEntities(EntitySpecPojo e, DocumentPojo f)
	 * 
	 * @param e
	 * @param f
	 * @return List<EntityPojo>
	 * @throws JSONException 
	 */
	private List<EntityPojo> getEntities(List<EntitySpecPojo> esps, DocumentPojo f) throws JSONException
	{
		//TODO (INF-1922): should I always create in a new list and then add on? because of the entity map below...
		
		// If the feed already has entities we want to add the new entities to the list of existing entities
		List<EntityPojo> entities = null;
		if (f.getEntities() != null) 
		{ 
			entities = f.getEntities(); 
		}
		// Otherwise we create a new arraylist to hold the new entities we are adding
		else 
		{ 
			entities = new ArrayList<EntityPojo>();
		}
		repopulateEntityCacheIfNeeded(f);
		_entitiesToDeleteMap = null; // (do this at start and end, in case of exceptions)

		// Iterate over each EntitySpecPojo and try to create an entity, or entities, from the data
		
		for (EntitySpecPojo esp : esps)
		{
			try {
				List<EntityPojo> tempEntities = getEntities(esp, f, _docPojo.getMetadataReadOnly());
				for (EntityPojo e : tempEntities)
				{
					entities.add(e);
				}
			}
			catch (Exception e) {
				logger.warn("getEntities caught exception:",e);
			} // (carry on, prob just a missing field in this doc)
		}
		boolean actuallyDeletedEnts = false;
		if (null != entities) {
			if (null != _entitiesToDeleteMap) { // need to delete some of these entities...
				Iterator<EntityPojo> it = entities.iterator();
				while (it.hasNext()) {
					EntityPojo ent = it.next();
					if (_entitiesToDeleteMap.contains(ent.getIndex())) {
						actuallyDeletedEnts = true;
						_entityMap.remove(ent.getIndex());
						_geoMap.remove(ent.getIndex());
						it.remove();
					}
				}
			}//TESTED (assoc_entity_delete_test)
			if (actuallyDeletedEnts && (null != f.getAssociations())) { // ugh need to check for matching associations
				Iterator<AssociationPojo> itAssoc = f.getAssociations().iterator();
				while (itAssoc.hasNext()) {
					AssociationPojo assoc = itAssoc.next();
					if ((null != assoc.getEntity1_index()) && _entitiesToDeleteMap.contains(assoc.getEntity1_index())) {
						itAssoc.remove();
						continue;
					}
					if ((null != assoc.getEntity2_index()) && _entitiesToDeleteMap.contains(assoc.getEntity2_index())) {
						itAssoc.remove();
						continue;
					}
					if ((null != assoc.getGeo_index()) && _entitiesToDeleteMap.contains(assoc.getGeo_index())) {
						if ((null == assoc.getEntity1_index()) || (null == assoc.getEntity2_index())) {
							itAssoc.remove();
						}
						else {
							assoc.setGeo_index(null);
							assoc.setGeotag(null);
							assoc.setGeo_sig(null);
						}						
						continue;
					}//TESTED (assoc_entity_delete_test:geo_only vs assoc_entity_delete_test:geo_event)
				}
			}//TESTED (assoc_entity_delete_test)
		}
		_entitiesToDeleteMap = null;
		return entities;
	}
	
	
	
	/**
	 * getEntities
	 * @param esp
	 * @param f
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	private List<EntityPojo> getEntities(EntitySpecPojo esp, DocumentPojo f, Object currObj)
	{
		List<EntityPojo> entities = new ArrayList<EntityPojo>();
		
		// Does the entity contain a list of entities to iterate over - 
		if (esp.getIterateOver() != null)
		{
			try
			{
				String iterateOver = esp.getIterateOver();

				// Check to see if the arrayRoot specified exists in the current doc before proceeding
				
				Object itEl = null;
				try {
					
					itEl = PropertyUtils.getProperty(currObj, iterateOver);
				}
				catch (Exception e) {} // carry on, trapped below...
				
				if (null == itEl) {
					return entities;
				} 
				logger.debug("getEntitis class itEl:"+itEl.getClass());
				Object[] entityRecords = null;
				if(itEl instanceof Object[]){
					entityRecords = (Object[])itEl;
				}
				else if(itEl instanceof List){
					entityRecords = (Object[])((List)itEl).toArray();
				}else{
					entityRecords = new Object[1];
					entityRecords[0]=itEl;					
				}
				//TESTED11
				if (entityRecords.length > 0) {

				// Get the type of object contained in EntityRecords[0]
				Class<?> objType = entityRecords[0].getClass();
				
				logger.debug("getEntitis2 class entityRecords[0]:"+entityRecords[0].getClass());

				// EntityRecords is a simple String[] array of entities
				
				if (String.class.isAssignableFrom(objType))
				{
					// Iterate over array elements and extract entities
					for (int i = 0; i < entityRecords.length; ++i) 
					{							
						String field = (String)entityRecords[i];
						long nIndex = Long.valueOf(i);
						
						if (null != esp.getType()) { // (else cannot be a valid entity, must just be a list)
							EntityPojo entity = getEntity(esp, field, String.valueOf(i), f);
							if (entity != null) entities.add(entity);	
						}
						
						// Does the association break out into multiple associations?
						if (esp.getEntities() != null)
						{
							// Iterate over the associations and call getAssociations recursively
							for (EntitySpecPojo subEsp : esp.getEntities())
							{	
								if (null != subEsp.getIterateOver()) {
									if (null == subEsp.getCreationCriteriaScript()) {
										_context.getHarvestStatus().logMessage(new StringBuffer("In iterator ").
												append(esp.getIterateOver()).append(", trying to loop over field '").
												append(subEsp.getIterateOver()).append("' in array of primitives.").toString(), true);
									}
									else {
										this.executeEntityAssociationValidation(subEsp.getCreationCriteriaScript(), field, Long.toString(nIndex));
									}
									// (any creation criteria script indicates user accepts it can be either) 
								}
								if (null != subEsp.getDisambiguated_name()) {
									EntityPojo entity = getEntity(subEsp, field, String.valueOf(i), f);
									if (entity != null) entities.add(entity);	
								}
							}										
						}//TESTED (error case, mixed object)
					}
				}

				
				 //  EntityRecords is a JSONArray
				 
				//else if (objType.equalsIgnoreCase("class org.json.JSONObject"))				
				else if (Map.class.isAssignableFrom(objType))
				{		
					// Iterate over array elements and extract entities
					for (int i = 0; i < entityRecords.length; ++i) 
					{
						// Get JSONObject containing entity fields and pass entityElement
						// into the script engine so scripts can access it
						Object savedIterator = null;
						if (compiledScriptFactory != null) 
						{
							_iterator = savedIterator = entityRecords[i];
						}

						if (null != esp.getType()) { // (else cannot be a valid entity, must just be a list)
							EntityPojo entity = getEntity(esp, null, String.valueOf(i), f);
							if (entity != null) entities.add(entity);
						}
						
						// Does the entity break out into multiple entities?
						if (esp.getEntities() != null)
						{
							// Iterate over the entities and call getEntities recursively
							for (EntitySpecPojo subEsp : esp.getEntities())
							{	
								_iterator = savedIterator; // (reset this)
								
								List<EntityPojo> subEntities = getEntities(subEsp, f, _iterator);
								for (EntityPojo e : subEntities)
								{
									entities.add(e);
								}
							}
						}
					} // entityRecords
				}

				if (_iterator != currObj) { // (ie at the top level)
					_iterator = null;
				}
			}
			}
			catch (Exception e)
			{
				//logger.error("Exception in getEntities: " , e);
			}
		}
		
		// Single entity
		else
		{
			// Does the entity break out into multiple entities?
			if (esp.getEntities() != null)
			{
				// Iterate over the entities and call getEntities recursively
				for (EntitySpecPojo subEsp : esp.getEntities())
				{	
					List<EntityPojo> subEntities = getEntities(subEsp, f,currObj);
					for (EntityPojo e : subEntities)
					{
						entities.add(e);
					}
				}
			}
			else
			{
				EntityPojo entity = getEntity(esp, null, null, f);
				if (entity != null) entities.add(entity);	
			}
		}
		
		return entities;
	}
	
	
	
	/**
	 * getEntity
	 * @param esp
	 * @param field
	 * @param index
	 * @param f
	 * @return
	 */
	private EntityPojo getEntity(EntitySpecPojo esp, String field, String index, DocumentPojo f)
	{
		boolean deleteThisEntity = false;
		
		// If the EntitySpecPojo or DocumentPojo is null return null
		if ((esp == null) || (f == null)) return null;
		
		try
		{
			EntityPojo e = new EntityPojo();
			
			// Parse creation criteria script to determine if the entity should be added
			if (esp.getCreationCriteriaScript() != null && JavaScriptUtils.containsScript(esp.getCreationCriteriaScript()))
			{
				boolean addEntity = executeEntityAssociationValidation(esp.getCreationCriteriaScript(), field, index);
				if (!addEntity) {
					return null;
				}
			}
			
			// Entity.disambiguous_name
			Object dpOrIt = _iterator != null ? _iterator: _docPojo ;
			String disambiguatedName = null;
			if (JavaScriptUtils.containsScript(esp.getDisambiguated_name()))
			{
				disambiguatedName = (String)getValueFromScript(esp.getDisambiguated_name(), field, index);
			}
			else
			{
				if ((_iterator != null) && (esp.getDisambiguated_name().startsWith("$metadata.") || esp.getDisambiguated_name().startsWith("${metadata."))) {
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage("Warning: in disambiguated_name, using global $metadata when iterating", true);
					}
				}
				// Field - passed in via simple string array from getEntities
				disambiguatedName = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getDisambiguated_name(), field);				
			}
			
			// Only proceed if disambiguousName contains a meaningful value
			if (disambiguatedName != null && disambiguatedName.length() > 0)
			{
				e.setDisambiguatedName(disambiguatedName);
			}
			else // Always log failure to get a dname - to remove this, specify a creationCriteriaScript
			{
				_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required disambiguated_name from: ").append(esp.getDisambiguated_name()).toString(), true);
				return null;
			}
			
			// Entity.frequency (count)
			String freq = "1";
			if (esp.getFrequency() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getFrequency()))
				{
					freq = getValueFromScript(esp.getFrequency(), field, index).toString();
				}
				else
				{
					freq = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getFrequency(), field);
				}
				// Since we've specified freq, we're going to enforce it
				if (null == freq) { // failed to get it
					if (null == esp.getCreationCriteriaScript()) {
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required frequency from: ").append(esp.getFrequency()).toString(), true);
						return null;
					}
				}
			}

			// Try converting the freq string value to its numeric (double) representation
			Double frequency = (double) 0;
			try  
			{ 
				frequency = Double.parseDouble(freq); 
			} 
			catch (Exception e1) 
			{
				this._context.getHarvestStatus().logMessage(e1.getMessage(), true);
				return null;
			}
			
			// Only proceed if frequency > 0
			if (frequency > 0)
			{
				e.setFrequency(frequency.longValue()); // Cast to long from double
			}
			else // delete the doc
			{ 
				deleteThisEntity = true;
			}  

			// Entity.actual_name
			String actualName = null;
			if (esp.getActual_name() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getActual_name()))
				{
					actualName = (String)getValueFromScript(esp.getActual_name(), field, index);
				}
				else
				{
					if ((_iterator != null) && (esp.getActual_name().startsWith("$metadata.") || esp.getActual_name().startsWith("${metadata."))) {
						if (_context.isStandalone()) { // (minor message, while debugging only)
							_context.getHarvestStatus().logMessage("Warning: in actual_name, using global $metadata when iterating", true);
						}
					}
					actualName = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getActual_name(), field);
				}
				// Since we've specified actual name, we're going to enforce it (unless otherwise specified)
				if (null == actualName) { // failed to get it
					if (null == esp.getCreationCriteriaScript()) {
						if (_context.isStandalone()) { // (minor message, while debugging only)
							_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required actual_name from: ").append(esp.getActual_name()).toString(), true);
						}
						return null;
					}
				}
			}
			// If actualName == null set it equal to disambiguousName
			if (actualName == null) actualName = disambiguatedName;
			e.setActual_name(actualName);
			
			// Entity.type
			String type = null;
			if (esp.getType() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getType()))
				{
					type = (String)getValueFromScript(esp.getType(), field, index);
				}
				else
				{
					type = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getType(), field);
				}
				// Since we've specified type, we're going to enforce it (unless otherwise specified)
				if (null == type) { // failed to get it
					if (null == esp.getCreationCriteriaScript()) {
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required type from: ").append(esp.getType()).toString(), true);
						return null;
					}
				}
			}
			else
			{
				type = "Keyword";
			}
			e.setType(type);
			
			// Entity.index
			String entityIndex = disambiguatedName + "/" + type;
			e.setIndex(entityIndex.toLowerCase());
			
			// Entity.dimension
			String dimension = null;
			if (esp.getDimension() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getDimension()))
				{
					dimension = (String)getValueFromScript(esp.getDimension(), field, index);
				}
				else
				{
					dimension = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getDimension(), field);
				}
				// Since we've specified dimension, we're going to enforce it (unless otherwise specified)
				if (null == dimension) { // failed to get it
					if (null == esp.getCreationCriteriaScript()) {
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required dimension from: ").append(esp.getDimension()).toString(), true);
						return null;
					}
				}
			}
			if (null == dimension) {
				try {
					e.setDimension(DimensionUtility.getDimensionByType(type));
				}
				catch (java.lang.IllegalArgumentException ex) {
					e.setDimension(EntityPojo.Dimension.What);									
				}
			}
			else {
				if (dimension.equalsIgnoreCase("delete")) {
					deleteThisEntity = true;
				}
				else {
					try {
						EntityPojo.Dimension enumDimension = EntityPojo.Dimension.valueOf(dimension);
						if (null == enumDimension) {
							_context.getHarvestStatus().logMessage(new StringBuffer("Invalid dimension: ").append(dimension).toString(), true);
							return null; // (invalid dimension)
						}
						else {
							e.setDimension(enumDimension);
						}
					}
					catch (Exception e2) {
						_context.getHarvestStatus().logMessage(new StringBuffer("Invalid dimension: ").append(dimension).toString(), true);
						return null; // (invalid dimension)					
					}
				}
			}
			
			// Now check if we already exist, discard if so:
			if (!deleteThisEntity && _entityMap.contains(e.getIndex())) {
				return null;
			}

			// Entity.relevance
			String relevance = "0";
			if (esp.getRelevance() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getRelevance()))
				{
					relevance = (String)getValueFromScript(esp.getRelevance(), field, index);
				}
				else
				{
					relevance = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getRelevance(), field);
				}
				// Since we've specified relevance, we're going to enforce it (unless otherwise specified)
				if (null == relevance) { // failed to get it
					if (null == esp.getCreationCriteriaScript()) {
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required relevance from: ").append(esp.getRelevance()).toString(), true);
						return null;
					}
				}
			}
			try {
				e.setRelevance(Double.parseDouble(relevance));
			}
			catch (Exception e1) {
				this._context.getHarvestStatus().logMessage(e1.getMessage(), true);
				return null;				
			}

			// Entity.sentiment (optional field)
			if (esp.getSentiment() != null)
			{
				String sentiment;
				if (JavaScriptUtils.containsScript(esp.getSentiment()))
				{
					sentiment = (String)getValueFromScript(esp.getSentiment(), field, index);
				}
				else
				{
					sentiment = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getSentiment(), field);
				}
				// (sentiment is optional, even if specified)
				if (null != sentiment) {
					try {
						double d = Double.parseDouble(sentiment);
						e.setSentiment(d);
						if (null == e.getSentiment()) {
							if (_context.isStandalone()) { // (minor message, while debugging only)
								_context.getHarvestStatus().logMessage(new StringBuffer("Invalid sentiment: ").append(sentiment).toString(), true);
							}							
						}
					}
					catch (Exception e1) {
						this._context.getHarvestStatus().logMessage(e1.getMessage(), true);
						return null;				
					}
				}
			}

			// Entity Link data:
			
			if (esp.getLinkdata() != null)
			{
				
				String linkdata = null;
				if (JavaScriptUtils.containsScript(esp.getLinkdata()))
				{
					linkdata = (String)getValueFromScript(esp.getLinkdata(), field, index);
				}
				else
				{
					linkdata = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getLinkdata(), field);
				}
				// linkdata is optional, even if specified
				if (null != linkdata) {
					String[] links = linkdata.split("\\s+");
					e.setSemanticLinks(Arrays.asList(links));
				}
			}
			
			
			// Extract Entity GEO or set Entity Geo equal to DocGeo if specified via useDocGeo
			if (esp.getGeotag() != null)
			{	
				GeoPojo geo = getEntityGeo(esp.getGeotag(), null, field);
				if (null != geo) {
					e.setGeotag(geo);
				}
				// (Allow this field to be intrinsically optional)
				
				// If no ontology type is specified, derive it from getEntityGeo:
				if (null == esp.getOntology_type()) {
					esp.setOntology_type(esp.getGeotag().getOntology_type());
				}
			}
			else if (esp.getUseDocGeo() == true)
			{
				GeoPojo geo = getEntityGeo(null, f, field);
				if (null != geo) {
					e.setGeotag(geo);
				}
				// (Allow this field to be intrinsically optional)
			}

			// Entity.ontological_type (
			String ontology_type = null;
			if (esp.getOntology_type() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getOntology_type()))
				{
					ontology_type = (String)getValueFromScript(esp.getOntology_type(), field, index);
				}
				else
				{
					ontology_type = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getOntology_type(), field);
				}
				// Allow this field to be intrinsically optional
			}
			// If ontological_type == null, go fetch it from the internal lookup
			if (ontology_type == null) {
				e.setOntology_type(GeoOntologyMapping.mapEntityToOntology(type));
			}
			else if ('p' == GeoOntologyMapping.encodeOntologyCode(ontology_type) && !ontology_type.equals("point")) {
				// In this case we don't recognize the ontology type so we'll overwrite it
				e.setOntology_type(GeoOntologyMapping.mapEntityToOntology(type));				
			}
			e.setOntology_type(ontology_type);			
						
			// Add the index and geotag to geomap to get used by associations with matching indexes
			if (deleteThisEntity) {				
				if (_entityMap.contains(e.getIndex())) {
					if (null == _entitiesToDeleteMap) {
						_entitiesToDeleteMap = new HashSet<String>();
					}
					_entitiesToDeleteMap.add(e.getIndex());
				}
				return null;
			}//TESTED (assoc_entity_delete_test)
			else {
				if (e.getGeotag() != null)
				{
					_geoMap.put(e.getIndex(), e.getGeotag());
				}
				_entityMap.add(e.getIndex());			
				return e;
			}//TESTED (*ent*.json) 
		}
		catch (Exception ex)
		{
			return null;
		}
	}
	
	
	
	/**
	 * getAssociations
	 * @param esps
	 * @param f
	 * @return
	 * @throws JSONException 
	 */
	private List<AssociationPojo> getAssociations(List<AssociationSpecPojo> esps, DocumentPojo f) throws JSONException
	{
		// If the feed already has associations we want to add the new associations to the list of existing associations
		List<AssociationPojo> associations = null;
		if (f.getAssociations() != null) 
		{ 
			associations = f.getAssociations(); 
		}
		// Otherwise we create a new arraylist to hold the new associations we are adding
		else 
		{ 
			associations = new ArrayList<AssociationPojo>();
		}
		repopulateEntityCacheIfNeeded(f);
		_assocsToDeleteMap = null; // (do this at start and end, in case of exceptions)

		// Iterate over each AssociationSpecPojo and try to create an entity, or entities, from the data
		for (AssociationSpecPojo esp : esps)
		{
			try {
				List<AssociationPojo> tempAssocs = getAssociations(esp, f, _docPojo.getMetadataReadOnly());
				if (null != tempAssocs) {
					for (AssociationPojo e : tempAssocs)
					{
						associations.add(e);
					}
				}
			}
			catch (Exception e) {} // (prob just a missing field)
		}		
		if (null != associations) {
			if (null != _assocsToDeleteMap) { // unfortunately need to delete som of these guys
				Iterator<AssociationPojo> it = associations.iterator();
				while (it.hasNext()) {
					AssociationPojo assoc = it.next();
					String assocSig = createAssocSignature(assoc);
					if (_assocsToDeleteMap.contains(assocSig)) {
						it.remove();
					}
				}
			}
		}//TESTED (assoc_entity_delete_test)
		_assocsToDeleteMap = null;
		
		return associations;
	}
	
	
	
	/**
	 * getAssociations(List<AssociationSpecPojo> esps, DocumentPojo f)
	 * @param esps
	 * @param f
	 * @return List<AssociationPojo>
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private List<AssociationPojo> getAssociations(AssociationSpecPojo esp, DocumentPojo f, Object currObj)
	{
		List<AssociationPojo> associations = new ArrayList<AssociationPojo>();
		try
		{
			//
			if (esp.getIterateOver() != null)
			{
				String iterateOver = esp.getIterateOver();

				String slashSplit[] = iterateOver.split("/");
				String commaSplit[] = iterateOver.split(",");

				// START - Multiplicative/Additive Association Creation
				// entity1/entity2/geo_index/time_start/time_end or entity1,entity2,geo_index,time_start,time_end
				if (slashSplit.length > 1 || commaSplit.length > 1)
				{
					ArrayList<String[]> assocsToCreate =  new ArrayList<String[]> ();

					// Multiplicative - entity1/entity2/geo_index/time_start/time_end
					if (slashSplit.length > 1)
					{
						assocsToCreate = getMultiplicativeAssociations(esp, iterateOver, f);	
					}

					// WARNING: This code has not been tested! It should work but...
					// Additive - entity1,entity2,geo_index,time_start,time_end
					else if (commaSplit.length > 1)
					{
						assocsToCreate = getAdditiveAssociations(esp, iterateOver, f);
					}
					
					// Iterate over each association String[] returned and (try to) create a new AssociationSpecPojo
					if (assocsToCreate != null)
					{
						for (String[] assocToCreate : assocsToCreate)
						{
							LinkedHashMap currIt = new LinkedHashMap();
							
							AssociationSpecPojo newAssoc = new AssociationSpecPojo();
							// Entity1
							if (assocToCreate[0] !=null) { 
								newAssoc.setEntity1_index(assocToCreate[0].replace("$", "${$}"));
								currIt.put("entity1_index", assocToCreate[0]);
							}
							else {
								newAssoc.setEntity1(esp.getEntity1());
								newAssoc.setEntity1_index(esp.getEntity1_index());
							}
							
							// Entity2
							if (assocToCreate[1] !=null) { 
								newAssoc.setEntity2_index(assocToCreate[1].replace("$", "${$}")); 
								currIt.put("entity2_index", assocToCreate[1]);
							}
							else {
								newAssoc.setEntity2(esp.getEntity2());
								newAssoc.setEntity2_index(esp.getEntity2_index());
							}
							
							// Geo_index
							if (assocToCreate[2] !=null) { 
								newAssoc.setGeo_index(assocToCreate[2].replace("$", "${$}")); 
								currIt.put("geo_index", assocToCreate[2]);
							}
							else { newAssoc.setGeo_index(esp.getGeo_index()); }
							
							// Time_start
							if (assocToCreate[3] !=null) { 
								newAssoc.setTime_start(assocToCreate[3].replace("$", "${$}"));
								currIt.put("time_start", assocToCreate[3]);
							}
							else { newAssoc.setTime_start(esp.getTime_start()); }
							
							// Time_end
							if (assocToCreate[4] !=null) { 
								newAssoc.setTime_end(assocToCreate[4].replace("$", "${$}"));
								currIt.put("time_end", assocToCreate[4]);
							}
							else { newAssoc.setTime_end(esp.getTime_end()); }
							
							// Misc. Fields to copy from the original pojo
							newAssoc.setCreationCriteriaScript(esp.getCreationCriteriaScript());
							newAssoc.setVerb(esp.getVerb());
							newAssoc.setVerb_category(esp.getVerb_category());
							newAssoc.setAssoc_type(esp.getAssoc_type());
							newAssoc.setGeotag(esp.getGeotag());

							// Create an association from the AssociationSpecPojo and document
							Object savedIterator = _iterator;
							if (null != compiledScriptFactory) { // (in case no script engine specified)
								_iterator = currIt;
							}
							AssociationPojo association = getAssociation(newAssoc, null, null, f);
							if (association != null) associations.add(association);
							_iterator = savedIterator;
						}
						//TESTED (including the ${$} escaping)
					}
				}
				// END - Multiplicative/Additive Association Creation

				//
				else if (null != currObj) // Single field iterateOver
				{
					try
					{
						// Check to see if the arrayRoot specified exists in the current doc before proceeding
						// Get array of association records from the specified root element
						
						Object itEl = null;
						try {
							itEl = PropertyUtils.getProperty(currObj, iterateOver);

						}
						catch (Exception e) {} // carry on, trapped below...
						
						if (null == itEl) {
							return associations;
						}
						logger.debug("getAssociations2 class itEl:"+itEl.getClass());
						//	JSONArray entityRecords = null;
						Object[] assocRecords = null;
						
						if(itEl instanceof Object[]){
							assocRecords = (Object[])itEl;
						}
						else if(itEl instanceof List){
							assocRecords = (Object[])((List)itEl).toArray();
						}else{
							assocRecords = new Object[1];
							assocRecords[0]=itEl;					
						}
						//TESTED						

						// Get the type of object contained in assocRecords[0]
						if (assocRecords.length > 0) {
							//String objType = assocRecords.get(0).getClass().toString();
							Class<?> objType = assocRecords[0].getClass();
							logger.debug("getAssopciations2 class entityRecords[0]:"+assocRecords[0].getClass());

							// EntityRecords is a simple String[] array of associations
							if (String.class.isAssignableFrom(objType))
							{
								// Iterate over array elements and extract associations
								for (int i = 0; i < assocRecords.length; ++i) 
								{
									String field = (String)assocRecords[i];
									long nIndex = Long.valueOf(i);
									
									if (null != esp.getVerb_category()) { // (ie a mandatory field is present)										
										AssociationPojo association = getAssociation(esp, field, nIndex, f);
										if (association != null) associations.add(association);
									}//TESTED
									
									// Does the association break out into multiple associations?
									if (esp.getAssociations() != null)
									{
										// Iterate over the associations and call getAssociations recursively
										for (AssociationSpecPojo subEsp : esp.getAssociations())
										{	
											if (null != subEsp.getIterateOver()) {
												if (null == subEsp.getCreationCriteriaScript()) {
													_context.getHarvestStatus().logMessage(new StringBuffer("In iterator ").
															append(esp.getIterateOver()).append(", trying to loop over field '").
															append(subEsp.getIterateOver()).append("' in array of primitives.").toString(), true);
												}
												else {
													this.executeEntityAssociationValidation(subEsp.getCreationCriteriaScript(), field, Long.toString(nIndex));
												}
												// (any creation criteria script indicates user accepts it can be either) 
											}
											if (null != subEsp.getVerb_category()) { // (ie a mandatory field is present)										
												AssociationPojo association = getAssociation(subEsp, field, nIndex, f);
												if (association != null) associations.add(association);
											}
										}										
									}//TESTED (error case)
								}
							}

							// EntityRecords is a JSONArray
							//else if (objType.equalsIgnoreCase("class org.json.JSONObject"))
							else if (Map.class.isAssignableFrom(objType))
							{
								// Iterate over array elements and extract associations
								for (int i = 0; i < assocRecords.length; ++i) 
								{
									// Get JSONObject containing association fields and pass assocElement
									// into the script engine so scripts can access it
									Object savedIterator = null;
									if (compiledScriptFactory != null) 
									{
										//_iterator = savedIterator = assocRecords.getJSONObject(i);
										_iterator = savedIterator = assocRecords[i];
									}

									if (null != esp.getVerb_category()) { // (ie a mandatory field is present)										
										AssociationPojo association = getAssociation(esp, null, Long.valueOf(i), f);
										if (association != null) associations.add(association);	
									}//TESTED
									
									// Does the association break out into multiple associations?
									if (esp.getAssociations() != null)
									{
										// Iterate over the associations and call getAssociations recursively
										for (AssociationSpecPojo subEsp : esp.getAssociations())
										{	
											_iterator = savedIterator; // (reset this)
											
											List<AssociationPojo> subAssocs = getAssociations(subEsp, f, _iterator);
											for (AssociationPojo e : subAssocs)
											{
												associations.add(e);
											}
										}
									}
									
								}//(else if is json object)
							}//(end if >0 array elements)

							if (_iterator != currObj) { // top level
								_iterator = null;
							}
						}
					}
					catch (Exception e)
					{
						//System.out.println(e.getMessage());
						//DEBUG (don't output log messages per doc)
						//logger.error("Exception: " + e.getMessage(), e);
					}
				}
			}

			// 
			else // No iterate over at all
			{
				AssociationPojo association = getAssociation(esp, null, null, f);
				if (association != null) associations.add(association);
			}			

			return associations;
		}
		catch (Exception e)
		{
			//DEBUG (don't output log messages per doc)
			//logger.error("Exception: " + e.getMessage());
			return null;
		}
	}
	
	
	
	/**
	 * getMultiplicativeAssociations
	 * @param iterateOver
	 * @param f
	 * @return
	 */
	private ArrayList<String[]> getMultiplicativeAssociations(AssociationSpecPojo esp, String iterateOver, DocumentPojo f)
	{
		// Split iterateOver into a list of fields
		String[] entityFields = iterateOver.split("/");
		
		// ArrayList to store association entities in and extract the entities (disambiguous names) from feed.entities
		HashMap<String, ArrayList<String>> entityLists = extractEntityLists(esp, entityFields, f); 
		
		// Calculate the total number of associations to create from the EntitySpecPojo
		Hashtable<String, Integer> assocCounts = getTotalNumberOfAssociations(entityLists, entityFields);
		int totalNumberOfAssociations = (Integer) assocCounts.get("totalNumberOfAssociations");
		
		if (totalNumberOfAssociations > 0)
		{
			ArrayList<String[]> assocsToCreate = new ArrayList<String[]> ();

			int entity1Number = 1;
			int entity2Number = 1;
			int geoIndexNumber = 1;
			int timeStartNumber = 1;
			int timeEndNumber = 1;
			
			for (int i = 0; i < totalNumberOfAssociations; i++)
			{
				try
				{
					String[] assocToCreate = new String[5];

					// Entity1
					if (entityLists.get("entity1") != null && entityLists.get("entity1").get(entity1Number - 1) != null)
					{
						assocToCreate[0] = entityLists.get("entity1").get(entity1Number - 1);
						if (((Integer) assocCounts.get("entity1Count") > 1) && (i % (Integer) assocCounts.get("entity1Repeat") == 0)) entity1Number++;
						if (entity1Number > entityLists.get("entity1").size()) entity1Number = 1;
					}
					
					// Entity2
					if (entityLists.get("entity2") != null && entityLists.get("entity2").get(entity2Number - 1) != null)
					{
						assocToCreate[1] = entityLists.get("entity2").get(entity2Number - 1);
						if (((Integer) assocCounts.get("entity2Count") > 1) && (i % (Integer) assocCounts.get("entity2Repeat") == 0)) entity2Number++;
						if (entity2Number > entityLists.get("entity2").size()) entity2Number = 1;
					}
					
					// Geo_Index
					if (entityLists.get("geo_index") != null && entityLists.get("geo_index").get(geoIndexNumber - 1) != null)
					{
						assocToCreate[2] = entityLists.get("geo_index").get(geoIndexNumber - 1);
						if (((Integer) assocCounts.get("geoIndexCount") > 1) && (i % (Integer) assocCounts.get("geoIndexCount") == 0)) geoIndexNumber++;
						if (geoIndexNumber > entityLists.get("geo_index").size()) geoIndexNumber = 1;
					}
					
					// Time_Start
					if (entityLists.get("time_start") != null && entityLists.get("time_start").get(timeStartNumber - 1) != null)
					{
						assocToCreate[3] = entityLists.get("time_start").get(timeStartNumber - 1);
						if (((Integer) assocCounts.get("timeStartCount") > 1) && (i % (Integer) assocCounts.get("timeStartCount") == 0)) timeStartNumber++;
						if (timeStartNumber > entityLists.get("time_start").size()) timeStartNumber = 1;
					}
					
					// Time_End
					if (entityLists.get("time_end") != null && entityLists.get("time_end").get(timeEndNumber - 1) != null)
					{
						assocToCreate[4] = entityLists.get("time_end").get(timeEndNumber - 1);
						if (((Integer) assocCounts.get("timeEndCount") > 1) && (i % (Integer) assocCounts.get("timeEndCount") == 0)) timeEndNumber++;
						if (timeEndNumber > entityLists.get("time_end").size()) timeEndNumber = 1;
					}

					assocsToCreate.add(assocToCreate);
				}
				catch (Exception e)
				{
					//e.printStackTrace();
					//System.out.println(e.getMessage());
					//logger.error("Exception: " + e.getMessage());
				}
			}
			return assocsToCreate;
		}
		else
		{
			return null;
		}
	}
	
	/**
	 * extractEntityLists
	 * @param esp
	 * @param entityFields
	 * @param f
	 * @return
	 */
	private HashMap<String, ArrayList<String>> extractEntityLists(AssociationSpecPojo esp, String[] entityFields, DocumentPojo f)
	{
		// ArrayList to store association entities in
		HashMap<String, ArrayList<String>> entityLists = new HashMap<String, ArrayList<String>>(); 
		
		// Get the list of entities from the feed
		List<EntityPojo> entities = f.getEntities();
		
		// These are the fields over which we are iterating
		for (String field : entityFields)
		{
			// Get the specified type for this field
			String typeValue = getFieldValueFromAssociationSpecPojo(esp, field);
			
			// Get the index for any entity that matches the type field
			ArrayList<String> indexes = new ArrayList<String>();
			if (typeValue != null)
			{
				for (EntityPojo e : entities)
				{
					if (e.getType().equalsIgnoreCase(typeValue))
					{
						if (null != e.getIndex()) {
							indexes.add(e.getIndex()); // (I think the code will always take this branch)
						}
						else { // (this is just a harmless safety net I think)
							indexes.add(new StringBuffer(e.getDisambiguatedName().toLowerCase()).append(typeValue.toLowerCase()).toString());
						}
					}
				}
				if (indexes.size() > 0) entityLists.put(field, indexes);
			}
		}
		//TESTED (see INF1360_test_source.json:test1 for entities, :test5 for geo_index)
		
		return entityLists;
	}
	
	/**
	 * getFieldValueFromAssociationSpecPojo
	 * @param esp
	 * @param field
	 * @return
	 */
	private String getFieldValueFromAssociationSpecPojo(AssociationSpecPojo esp, String field)
	{
		if (field.equalsIgnoreCase("entity1"))
		{
			return esp.getEntity1();
		}
		else if (field.equalsIgnoreCase("entity2"))
		{
			return esp.getEntity2();
		}
		else if (field.equalsIgnoreCase("geo_index"))
		{
			return esp.getGeo_index();
		}
		else if (field.equalsIgnoreCase("time_start"))
		{
			return esp.getTime_start();
		}
		else if (field.equalsIgnoreCase("time_end"))
		{
			return esp.getTime_end();
		}
		else
		{
			return null;
		}
	}
	
	
	
	
	/**
	 * getTotalNumberOfAssociations
	 * @param entityLists
	 * @return
	 */
	private Hashtable<String, Integer> getTotalNumberOfAssociations(HashMap<String, ArrayList<String>> entityLists, String[] entityFields)
	{
		// Create Hashtable to hold count values referenced by name: i.e. totalNumberOfAssociations
		Hashtable<String, Integer> retVal = new Hashtable<String, Integer>();
		
		//
		int entity1_count = 0;
		int entity2_count = 0;
		int geo_index_count = 0;
		int time_start_count = 0;
		int time_end_count = 0;
		
		// Count up the total number of associations that need to be created 
		// Total Number of Associations = entity1 * entity2 * geo_index * time_start * time_end
		// Note: Only calculates based on the fields passed in the entityFields String[] and
		// the number of matching values in entityLists. If one of those values is 0 then the
		// total number of associations = 0
		int totalAssocs = 1;
		for (String field : entityFields)
		{
			if (field.equalsIgnoreCase("entity1")) 
			{
				entity1_count = (entityLists.get("entity1") != null) ? entityLists.get("entity1").size() : 0;
				totalAssocs = totalAssocs * entity1_count;
			}
			
			if (field.equalsIgnoreCase("entity2"))
			{
				entity2_count = (entityLists.get("entity2") != null) ? entityLists.get("entity2").size() : 0;
				totalAssocs = totalAssocs * entity2_count;
			}
			
			if (field.equalsIgnoreCase("geo_index"))
			{
				geo_index_count = (entityLists.get("geo_index") != null) ? entityLists.get("geo_index").size() : 0;
				totalAssocs = totalAssocs * geo_index_count;
			}
			
			if (field.equalsIgnoreCase("time_start"))
			{
				time_start_count = (entityLists.get("time_start") != null) ? entityLists.get("time_start").size() : 0;
				totalAssocs = totalAssocs * time_start_count;
			}
			
			if (field.equalsIgnoreCase("time_end"))
			{
				time_end_count = (entityLists.get("time_end") != null) ? entityLists.get("time_end").size() : 0;
				totalAssocs = totalAssocs * time_end_count;
			}
		}

		// Add total number of associations to the HashTable and return if the val == 0
		retVal.put("totalNumberOfAssociations", totalAssocs);
		if (totalAssocs == 0) return retVal;

		if (entity1_count == 0) entity1_count = 1;
		if (entity2_count == 0) entity2_count = 1;
		if (geo_index_count == 0) geo_index_count = 1;
		if (time_start_count == 0) time_start_count = 1;
		if (time_end_count == 0) time_end_count = 1;
		
		// Entity1
		Double repeat = (double) (totalAssocs / entity1_count);
		retVal.put("entity1Repeat", repeat.intValue());
		retVal.put("entity1Count", entity1_count);
		
		// Entity2
		repeat = (double) (totalAssocs / entity1_count / entity2_count); 
		retVal.put("entity2Repeat", repeat.intValue());
		retVal.put("entity2Count", entity2_count);
		
		// Geo_Index
		repeat = (double) (totalAssocs / entity1_count / entity2_count / geo_index_count);  
		retVal.put("geoIndexRepeat", repeat.intValue());
		retVal.put("geoIndexCount", geo_index_count);
		
		// Time_Start
		repeat = (double) (totalAssocs / entity1_count / entity2_count / geo_index_count / time_start_count);  
		retVal.put("timeStartRepeat", repeat.intValue());
		retVal.put("timeStartCount", time_start_count);
		
		// Time_End
		repeat = (double) (totalAssocs / entity1_count / entity2_count / geo_index_count / time_start_count / time_end_count);  
		retVal.put("timeEndRepeat", repeat.intValue());
		retVal.put("timeEndCount", time_end_count);
		
		return retVal;
	}
	
	
	
	
	/**
	 * getAdditiveAssociations
	 * @param iterateOver
	 * @param f
	 * @return
	 */
	private ArrayList<String[]> getAdditiveAssociations(AssociationSpecPojo esp, String iterateOver, DocumentPojo f)
	{
		// Split iterateOver into a list of entities on ','
		String[] entityFields = iterateOver.split(",");
		
		// ArrayList to store association entities in and extract the entities (disambiguous names) from doc.entities
		HashMap<String, ArrayList<String>> entityLists = extractEntityLists(esp, entityFields, f); 
		
		int itemCount = 0;
		if (entityLists.size() > 0)
		{
			itemCount = (entityLists.get(entityFields[0]) != null) ? entityLists.get(entityFields[0]).size() : 0;
			
			// Get an ArrayList<String> from entity1, entity2, geo_index, time_start and time_end fields as appropriate
			ArrayList<String> entity1 = (entityLists.get("entity1") != null) ? entityLists.get("entity1") : null;
			ArrayList<String> entity2 = (entityLists.get("entity2") != null) ? entityLists.get("entity2") : null;
			ArrayList<String> geo_index = (entityLists.get("geo_index") != null) ? entityLists.get("geo_index") : null;
			ArrayList<String> time_start = (entityLists.get("time_start") != null) ? entityLists.get("time_start") : null;
			ArrayList<String> time_end = (entityLists.get("time_end") != null) ? entityLists.get("time_end") : null;
			
			ArrayList<String[]> assocsToCreate = new ArrayList<String[]>();
			for (int i = 0; i < itemCount; i++)
			{
				String[] assocToCreate = new String[5];
				if (entity1 != null && entity1.get(i) != null) assocToCreate[0] = entity1.get(i);
				if (entity2 != null && entity2.get(i) != null) assocToCreate[1] = entity2.get(i);
				if (geo_index != null && geo_index.get(i) != null) assocToCreate[2] = geo_index.get(i);
				if (time_start != null && time_start.get(i) != null) assocToCreate[3] = time_start.get(i);
				if (time_end != null && time_end.get(i) != null) assocToCreate[4] = time_end.get(i);
				
				// Only add assocToCreate to associationsToCreate if each field passed via entityFields has a value 
				boolean addAssocToCreate = true;
				for (String s : entityFields)
				{
					if (s.equalsIgnoreCase("entity1") && assocToCreate[0] == null) { addAssocToCreate = false; break; }
					if (s.equalsIgnoreCase("entity2") && assocToCreate[1] == null) { addAssocToCreate = false; break; }
					if (s.equalsIgnoreCase("geo_index") && assocToCreate[2] == null) { addAssocToCreate = false; break; }
					if (s.equalsIgnoreCase("time_start") && assocToCreate[3] == null) { addAssocToCreate = false; break; }
					if (s.equalsIgnoreCase("time_end") && assocToCreate[4] == null) { addAssocToCreate = false; break; }
				}
				
				if (addAssocToCreate) assocsToCreate.add(assocToCreate);
			}
			return assocsToCreate;
		}
		else
		{
			return null;
		}
	}
	
	

	/**
	 * getAssociation
	 * @param esp
	 * @param field
	 * @param count
	 * @param f
	 * @return
	 */
	private AssociationPojo getAssociation(AssociationSpecPojo esp, String field, Long count, DocumentPojo f) 
	{
		String index = (count != null) ? count.toString() : null;
		try
		{
			AssociationPojo e = new AssociationPojo();
			
			// If the AssociationSpecPojo has a creation criteria script check the association for validity
			if (esp.getCreationCriteriaScript() != null && JavaScriptUtils.containsScript(esp.getCreationCriteriaScript()))
			{
				boolean addAssoc = executeEntityAssociationValidation(esp.getCreationCriteriaScript(), field, index);
				if (!addAssoc) {
					return null;
				}
			}			
			
			boolean bDontResolveToIndices = false; // (can always override to summary)
			if (null != esp.getAssoc_type() && (esp.getAssoc_type().equalsIgnoreCase("summary"))) {
				bDontResolveToIndices = true;
			}

			Object dpOrIt = _iterator != null ? _iterator: _docPojo ;
			// Assoc.entity1
			if ((esp.getEntity1() != null) || (esp.getEntity1_index() != null))
			{
				// Association.entity1_index
				if (esp.getEntity1_index() != null)
				{
					if (JavaScriptUtils.containsScript(esp.getEntity1_index()))
					{
						String s = (String)getValueFromScript(esp.getEntity1_index(), field, index);
						if (null != s) e.setEntity1_index(s.toLowerCase());
					}
					else
					{
						if ((_iterator != null) && (esp.getEntity1_index().startsWith("$metadata.") || esp.getEntity1_index().startsWith("${metadata."))) {
							if (_context.isStandalone()) { // (minor message, while debugging only)
								_context.getHarvestStatus().logMessage("Warning: in entity1_index, using global $metadata when iterating", true);
							}
						}
						String s = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getEntity1_index(), field);
						if (null != s) e.setEntity1_index(s.toLowerCase());
					}
					if (null != e.getEntity1_index()) { // Convert to entity1
						int nTypeIndex = e.getEntity1_index().lastIndexOf('/');
						if (nTypeIndex > 0) {
							e.setEntity1(e.getEntity1_index().substring(0, nTypeIndex));
							if (!_entityMap.contains(e.getEntity1_index())) { // Needs to correlate with an entity
								StringBuffer error =  new StringBuffer("Failed to correlate entity1_index with: ").append(esp.getEntity1_index());
								if (_context.isStandalone()) {
									error.append(" using ").append(e.getEntity1_index());									
								}
								_context.getHarvestStatus().logMessage(error.toString(), true);
								e.setEntity1_index(null);							
							}//TESTED (INF1360_test_source.json:test8)
						}
						else { // index must be malformed
							StringBuffer error =  new StringBuffer("Malformed entity1_index with: ").append(esp.getEntity1_index());
							if (_context.isStandalone()) {
								error.append(" using ").append(e.getEntity1_index());									
							}
							_context.getHarvestStatus().logMessage(error.toString(), true);
							e.setEntity1_index(null);
						}
					}
				}//TESTED (see INF1360_test_source.json:test2)
				
				// entity1				
				if (null != esp.getEntity1()) {
					
					if (JavaScriptUtils.containsScript(esp.getEntity1()))
					{
						e.setEntity1((String)getValueFromScript(esp.getEntity1(), field, index));
					}
					else
					{
						if ((_iterator != null) && (esp.getEntity1().startsWith("$metadata.") || esp.getEntity1().startsWith("${metadata."))) {
							if (_context.isStandalone()) { // (minor message, while debugging only)
								_context.getHarvestStatus().logMessage("Warning: in entity1, using global $metadata when iterating", true);
							}
						}
						e.setEntity1(FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getEntity1(), field));
					}
					
					if (!bDontResolveToIndices && (null == e.getEntity1_index()))
					{
						// Try using the entity.disambiguated name, this isn't perfect because 2 entities with different
						// types can have different dnames, but we'll try and then abandon if we get multiple hits
						int nHits = 0;
						String matchingIndex = null;
						for (EntityPojo entity : f.getEntities())
						{
							if (entity.getDisambiguatedName().equalsIgnoreCase(e.getEntity1()))
							{
								nHits++;
								if (1 == nHits) {
									matchingIndex = entity.getIndex();
									e.setEntity1_index(entity.getIndex());
								}
								else if (!matchingIndex.equals(entity.getIndex())) { // Ambiguous reference so bail out 
									StringBuffer error =  new StringBuffer("Failed entity1_index disambiguation with: ").append(esp.getEntity1());
									if (_context.isStandalone()) {
										error.append(" using ").append(e.getEntity1());									
									}
									_context.getHarvestStatus().logMessage(error.toString(), true);

									e.setEntity1_index(null);
									break;
								}
							}
						} // (end loop across all indices)
					}//TESTED (success and fail cases, see INF1360_test_source.json:test3)
					
				} // (end no entity1_index extracted, entity1 specified)
				
				// Quality checks:
				
				if ((esp.getEntity1() != null) && (null == e.getEntity1()) && (null == esp.getCreationCriteriaScript())) {
					// Specified this (entity1), so going to insist on it
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required entity1 from: ").append(esp.getEntity1()).toString(), true);
					}
					return null;
				}
				if ((esp.getEntity1_index() != null) && (null == e.getEntity1_index()) && (null == esp.getCreationCriteriaScript())) {
					// Specified this (entity1_index), so going to insist on it
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required entity1_index from: ").append(esp.getEntity1_index()).toString(), true);
					}
					return null;
				}
				//TESTED INF1360_test_source:test7 (no criteria), test8 (criteria)
				
			} // (end entity1)
			
			// Assoc.entity2
			if ((esp.getEntity2() != null) || (esp.getEntity2_index() != null))
			{
				// Association.entity2_index
				if (esp.getEntity2_index() != null)
				{
					if (JavaScriptUtils.containsScript(esp.getEntity2_index()))
					{
						String s = (String)getValueFromScript(esp.getEntity2_index(), field, index);
						if (null != s) e.setEntity2_index(s.toLowerCase());
					}
					else
					{
						if ((_iterator != null) && (esp.getEntity2_index().startsWith("$metadata.") || esp.getEntity2_index().startsWith("${metadata."))) {
							if (_context.isStandalone()) { // (minor message, while debugging only)
								_context.getHarvestStatus().logMessage("Warning: in entity2_index, using global $metadata when iterating", true);
							}
						}
						String s = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getEntity2_index(), field);
						if (null != s) e.setEntity2_index(s.toLowerCase());
					}
					if (null != e.getEntity2_index()) { // Convert to entity2
						int nTypeIndex = e.getEntity2_index().lastIndexOf('/');
						if (nTypeIndex > 0) {
							e.setEntity2(e.getEntity2_index().substring(0, nTypeIndex));
							if (!_entityMap.contains(e.getEntity2_index())) { // Needs to correlate with an entity
								StringBuffer error =  new StringBuffer("Failed to correlate entity2_index with: ").append(esp.getEntity2_index());
								if (_context.isStandalone()) {
									error.append(" using ").append(e.getEntity2_index());									
								}
								_context.getHarvestStatus().logMessage(error.toString(), true);
								e.setEntity2_index(null);							
							}//TESTED (INF1360_test_source.json:test8)
						}
						else { // index must be malformed
							StringBuffer error =  new StringBuffer("Malformed entity2_index with: ").append(esp.getEntity2_index());
							if (_context.isStandalone()) {
								error.append(" using ").append(e.getEntity2_index());									
							}
							_context.getHarvestStatus().logMessage(error.toString(), true);
							e.setEntity2_index(null);
						}
					}
				}//TESTED (see INF1360_test_source.json:test2)
				
				// entity2				
				if (null != esp.getEntity2()) {
					
					if (JavaScriptUtils.containsScript(esp.getEntity2()))
					{
						e.setEntity2((String)getValueFromScript(esp.getEntity2(), field, index));
					}
					else
					{
						if ((_iterator != null) && (esp.getEntity2().startsWith("$metadata.") || esp.getEntity2().startsWith("${metadata."))) {
							if (_context.isStandalone()) { // (minor message, while debugging only)
								_context.getHarvestStatus().logMessage("Warning: in entity2, using global $metadata when iterating", true);
							}
						}
						e.setEntity2(FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getEntity2(), field));
					}
					
					if (!bDontResolveToIndices && (null == e.getEntity2_index()))
					{
						// Try using the entity.disambiguated name, this isn't perfect because 2 entities with different
						// types can have different dnames, but we'll try and then abandon if we get multiple hits
						int nHits = 0;
						String matchingIndex = null;
						for (EntityPojo entity : f.getEntities())
						{
							if (entity.getDisambiguatedName().equalsIgnoreCase(e.getEntity2()))
							{
								nHits++;
								if (1 == nHits) {
									matchingIndex = entity.getIndex();
									e.setEntity2_index(entity.getIndex());
								}
								else if (!matchingIndex.equals(entity.getIndex())) { // Ambiguous reference so bail out 
									StringBuffer error =  new StringBuffer("Failed entity2_index disambiguation with: ").append(esp.getEntity2());
									if (_context.isStandalone()) {
										error.append(" using ").append(e.getEntity2());									
									}
									_context.getHarvestStatus().logMessage(error.toString(), true);
									
									e.setEntity2_index(null);
									break;
								}
							}
						} // (end loop across all indices)
					}//TESTED (success and fail cases, see INF1360_test_source.json:test3)
					
				} // (end no entity2_index extracted, entity2 specified)
				
				// Quality checks:
				
				if ((esp.getEntity2() != null) && (null == e.getEntity2()) && (null == esp.getCreationCriteriaScript())) {
					// Specified this (entity2), so going to insist on it
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required entity2 from: ").append(esp.getEntity2()).toString(), true);
					}
					return null;
				}
				if ((esp.getEntity2_index() != null) && (null == e.getEntity2_index()) && (null == esp.getCreationCriteriaScript())) {
					// Specified this (entity2_index), so going to insist on it
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required entity2_index from: ").append(esp.getEntity2_index()).toString(), true);
					}
					return null;
				}
				//TESTED INF1360_test_source:test7 (no criteria), test8 (criteria)
				
			} // (end entity2)
			
			// Association.verb
			if (esp.getVerb() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getVerb()))
				{
					e.setVerb((String)getValueFromScript(esp.getVerb(), field, index));
				}
				else
				{
					e.setVerb(FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getVerb(), field));
				}
				if ((null == e.getVerb()) && (null == esp.getCreationCriteriaScript())) {
					// Specified this, so going to insist on it
					if (_context.isStandalone()) { // (minor message, while debugging only)
						_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required verb from: ").append(esp.getVerb()).toString(), true);
					}
					return null;
				}
			}
			
			// Association.verb_category
			if (esp.getVerb_category() != null)
			{
				if (JavaScriptUtils.containsScript(esp.getVerb_category()))
				{
					String s = (String)getValueFromScript(esp.getVerb_category(), field, index);
					if (null != s) e.setVerb_category(s.toLowerCase());
				}
				else
				{
					String s = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getVerb_category(), field);
					if (null != s) e.setVerb_category(s.toLowerCase());
				}
			}
			if (null == e.getVerb_category()) { // Needed: verb category (get from verb if not specified)
				_context.getHarvestStatus().logMessage(new StringBuffer("Failed to get required verb_category from: ").append(esp.getVerb_category()).toString(), true);
				return null;
			}
			if (null == e.getVerb()) { // set from verb cat
				e.setVerb(e.getVerb_category());
			}
			
			// Entity.start_time
			if (esp.getTime_start() != null)
			{
				try {
					String startTimeString = null;
					if (JavaScriptUtils.containsScript(esp.getTime_start()))
					{
						startTimeString = (String)getValueFromScript(esp.getTime_start(), field, index);
					}
					else
					{
						startTimeString = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getTime_start(), field);
					}
					if (null != startTimeString) {
						e.setTime_start(DateUtility.getIsoDateString(startTimeString));
						if (null == e.getTime_start()) {
							_context.getHarvestStatus().logMessage("getTime_start: parse (ISO/long) fail vs" + startTimeString, true);							
						}
					}
				}
				catch (Exception ts) {
					_context.getHarvestStatus().logMessage("getTime_start: " + ts.getMessage(), true);
				}

				// Allow this to be intrinsically optional
			}
			
			// Entity.end_time
			if (esp.getTime_end() != null)
			{		
				try {
					String endTimeString = null;
					if (JavaScriptUtils.containsScript(esp.getTime_end()))
					{
						endTimeString = (String)getValueFromScript(esp.getTime_end(), field, index);
					}
					else
					{
						endTimeString = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getTime_end(), field);
					}
					if (null != endTimeString) {
						e.setTime_end(DateUtility.getIsoDateString(endTimeString));
						if (null == e.getTime_end()) {
							_context.getHarvestStatus().logMessage("getTime_start: parse (ISO/long) fail vs" + endTimeString, true);							
						}
					}
					// Allow this to be intrinsically optional
				}
				catch (Exception ts) {
					_context.getHarvestStatus().logMessage("getTime_start: " + ts.getMessage(), true);
				}
			}
			
			
			// Entity.geo_index
			if (esp.getGeo_index() != null)
			{				
				String geo_entity = null;
				if (JavaScriptUtils.containsScript(esp.getGeo_index()))
				{
					geo_entity = (String)getValueFromScript(esp.getGeo_index(), field, index);
				}
				else
				{
					if ((_iterator != null) && (esp.getGeo_index().startsWith("$metadata.") || esp.getGeo_index().startsWith("${metadata."))) {
						if (_context.isStandalone()) { // (minor message, while debugging only)
							_context.getHarvestStatus().logMessage("Warning: in geo_index, using global $metadata when iterating", true);
						}
					}
					geo_entity = FieldExtractor.getFormattedTextFromField(dpOrIt,esp.getGeo_index(), field);
				}
				if (null != geo_entity) {
					geo_entity = geo_entity.toLowerCase();
					if (geo_entity.lastIndexOf('/') < 0) {
						StringBuffer error =  new StringBuffer("Malformed entity2_index with: ").append(esp.getGeo_index());
						if (_context.isStandalone()) {
							error.append(" using ").append(geo_entity);									
						}
						_context.getHarvestStatus().logMessage(error.toString(), true);

						geo_entity = null;
					}
					if (!_entityMap.contains(geo_entity)) {
						StringBuffer error =  new StringBuffer("Failed to disambiguate geo_index with: ").append(esp.getGeo_index());
						if (_context.isStandalone()) {
							error.append(" using ").append(geo_entity);									
						}
						_context.getHarvestStatus().logMessage(error.toString(), true);

						geo_entity = null;						
					}
					//TESTED (INF1360_test_source:test4b)
				}
				//TESTED (INF1360_test_source:test4, test5, test6)
				
				if (null != geo_entity) e.setGeo_index(geo_entity);
				GeoPojo s1 = _geoMap.get(geo_entity); 
				e.setGeotag(s1);
				//TESTED (INF1360_test_source:test4)
				
				// Allow this to be intrinsically optional
			}
			
			// Get geo information based on geo tag
			if (e.getGeotag() == null)
			{
				// Extract association geoTag if it exists in the association
				if (esp.getGeotag() != null)
				{	
					e.setGeotag(getEntityGeo(esp.getGeotag(), null, field));
				}
				// Otherwise search geoMap on index (entity1_index, entity2_index) for a geoTag
				else
				{
					if (e.getEntity1_index() != null || e.getEntity2_index() != null)
					{
						GeoPojo s1 = _geoMap.get(e.getEntity1_index()); 
						if (s1 != null) 
						{
							e.setGeotag(s1);
							e.setGeo_index(e.getEntity1_index());
						}
						else {
							GeoPojo s2 = _geoMap.get(e.getEntity2_index()); 
							if (s2 != null) 
							{
								e.setGeotag(s2);
								e.setGeo_index(e.getEntity2_index());
							}
						}
					}
				}
				// Allow this to be intrinsically optional
			}

			// If all the indexes are null don't add the association
			if (e.getEntity1_index() == null && e.getEntity2_index() == null && e.getGeo_index() == null) {
				if (bDontResolveToIndices  && _context.isStandalone()) { // (minor message, while debugging only)
					_context.getHarvestStatus().logMessage("Warning: for summaries, at least one entity must be manually specified as an index", true);
				}
				return null;
			}
			
			// Set "delete" to remove any existing values
			if (null != esp.getAssoc_type() && (esp.getAssoc_type().equalsIgnoreCase("delete"))) {
				if (null == _assocsToDeleteMap) {
					_assocsToDeleteMap = new HashSet<String>();
				}
				_assocsToDeleteMap.add(createAssocSignature(e));
				return null;
			}//TESTED (assoc_entity_delete_test)
			
			// Calculate association type
			if (bDontResolveToIndices) {
				e.setAssociation_type("Summary");				
			}
			else {				
				e.setAssociation_type(AssociationUtils.getAssocType(e));
				if (null != esp.getAssoc_type()) {
					if (!e.getAssociation_type().equals("Summary")) {
						// Allowed to switch event<->fact
						if (esp.getAssoc_type().equalsIgnoreCase("fact")) {
							e.setAssociation_type("Fact");											
						}
						else if (esp.getAssoc_type().equalsIgnoreCase("event")) {
							e.setAssociation_type("Event");																		
						}
					}
				}
			}			
			return e;
		}
		catch (Exception e)
		{
			// This can happen as part of normal logic flow
			// TODO comment out
			logger.warn("Exception: ",e);
			return null;
		}
	}
		

	/**
	 * getValueFromScript
	 * @param script
	 * @param value
	 * @param index
	 * @return
	 */
	private Object getValueFromScript(String script, String value, String index) 
	{
		return getValueFromScript(script, value, index, true);
	}

	private Object getValueFromScript(String script, String value, String index, boolean errorOnNull) 
	{
		Object retVal = null;
		fillScriptContext(value,index);
		try
		{
			
			retVal = compiledScriptFactory.executeCompiledScript(script);
			if (errorOnNull && (null == retVal) && _context.isStandalone()) { // Display warning:
				StringBuffer error = new StringBuffer("Failed to get value from: ");
				error.append("script=").append(script).append("; iterator=").append(null==_iterator?"null":_iterator.toString()).
										append("; value=").append(null==value?"null":value).
										append("; index=").append(index == null?_iteratorIndex:index);
				
				_context.getHarvestStatus().logMessage(error.toString(), true);
			}
		}
		catch (Exception e)
		{
			//e.printStackTrace();
			
			StringBuffer error = HarvestExceptionUtils.createExceptionMessage(e);
			error.append(": script=").append(script);
			if (_context.isStandalone()) { //  Standalone mode, provide more details
				error.append("; iterator=").append(null==_iterator?"null":_iterator.toString()).
											append("; value=").append(null==value?"null":value).
											append("; index=").append(index == null?_iteratorIndex:index);
			}
			_context.getHarvestStatus().logMessage(error.toString(), true);
		}
		return retVal;

	}


	/**
	 * getDocGeo(DocGeoSpecPojo d)
	 * Convert the contents of a DocGeoSpecPojo to a GeoJSONPojo, i.e. return
	 * latitude and longitude for a feed
	 * @param d DocGeoSpecPojo
	 * @return GeoJSONPojo
	 */
	private GeoPojo getDocGeo(GeoSpecPojo d)
	{
		GeoPojo docGeo = new GeoPojo();
		String latValue = null;
		String lonValue = null;
		
		try
		{

			// The DocSpecGeoPojo already has lat and lon so we just need to retrieve the values
			if ((d.getLat() != null) && (d.getLon() != null))
			{
				if (JavaScriptUtils.containsScript(d.getLat()))
				{
					latValue = (String)getValueFromScript(d.getLat(), null, null);
				}
				else
				{
					latValue = FieldExtractor.getStringFromDocumentField(_docPojo,d.getLat(), null);
				}

				if (JavaScriptUtils.containsScript(d.getLat()))
				{
					lonValue = (String)getValueFromScript(d.getLon(), null, null);
				}
				else
				{
					lonValue = FieldExtractor.getStringFromDocumentField(_docPojo,d.getLon(), null);
				}
			}

			// Try and retrieve lat and lon using city, state, country values
			else
			{
				String city, region, country, countryCode = null;
				
				// Create a GeoReferencePojo from the DocSpecGeo object
				GeoFeaturePojo g = new GeoFeaturePojo();

				if (d.getCity() != null)
				{
					if (JavaScriptUtils.containsScript(d.getCity()))
					{
						city = (String)getValueFromScript(d.getCity(), null, null);
					}
					else
					{
						city = FieldExtractor.getFormattedTextFromField(_docPojo,d.getCity(), null);
					}

					g.setCity(city);
					g.setSearch_field(city);
				}

				if (d.getStateProvince() != null)
				{
					if (JavaScriptUtils.containsScript(d.getStateProvince()))
					{
						region = (String)getValueFromScript(d.getStateProvince(), null, null);
					}
					else
					{
						region = FieldExtractor.getFormattedTextFromField(_docPojo,d.getStateProvince(), null);
					}

					g.setRegion(region);
					if (g.getSearch_field() == null) g.setSearch_field(region);
				}

				if (d.getCountry() != null)
				{
					if (JavaScriptUtils.containsScript(d.getCountry()))
					{
						country = (String)getValueFromScript(d.getCountry(), null, null);
					}
					else
					{
						country = FieldExtractor.getFormattedTextFromField(_docPojo,d.getCountry(), null);
					}

					g.setCountry(country);
					if (g.getSearch_field() == null) g.setSearch_field(country);
				}

				if (d.getCountryCode() != null)
				{
					if (JavaScriptUtils.containsScript(d.getCountryCode()))
					{
						countryCode = (String)getValueFromScript(d.getCountryCode(), null, null);
					}
					else
					{
						countryCode = FieldExtractor.getFormattedTextFromField(_docPojo,d.getCountryCode(), null);
					}

					g.setCountry_code(countryCode);
					if (g.getSearch_field() == null) g.setSearch_field(countryCode);
				}

				// Send the GeoReferencePojo to enrichGeoInfo to attempt to get lat and lon values
				boolean bStrictMatch = (null == d.getStrictMatch()) || d.getStrictMatch();
				List<GeoFeaturePojo> gList = GeoReference.enrichGeoInfo(g, bStrictMatch, true, 1);
				latValue = gList.get(0).getGeoindex().lat.toString();
				lonValue = gList.get(0).getGeoindex().lon.toString();
			}

			// Set lat and long in DocGeo if possible
			docGeo.lat = Double.parseDouble(latValue);
			docGeo.lon = Double.parseDouble(lonValue);
			
			if (docGeo.lat == 0 && docGeo.lon == 0) docGeo = null; // Don't save 0,0 vals
		}
		catch (Exception e)
		{
			if (null != d.getAlternatives()) {
				for (GeoSpecPojo altIn: d.getAlternatives()) {
					GeoPojo altOut =  getDocGeo(altIn);
					if (null != altOut) {
						return altOut;
					}
				}
			}			
			docGeo = null;
		}
		return docGeo;
	}
	
	
	

	/**
	 * getEntityGeo
	 * Get GeoPojo object for entities and associations
	 * @param gsp
	 * @return
	 */
	private GeoPojo getEntityGeo(GeoSpecPojo gsp, DocumentPojo f, String field)
	{
		try
		{
			GeoPojo g = null;
			Double dLat = (double) 0;
			Double dLon = (double) 0;
			
			if (gsp != null)
			{
				Object dpOrIt = _iterator != null ? _iterator:_docPojo;
				String latValue = null;
				String lonValue = null;
				// The GeoSpecPojo already has lat and lon so we just need to retrieve the values
				if ((gsp.getLat() != null) && (gsp.getLon() != null)) {
					if (JavaScriptUtils.containsScript(gsp.getLat()))
					{
						latValue = (String)getValueFromScript(gsp.getLat(), null, null);
					}
					else
					{
						latValue = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getLat(), field);
					}
	
					if (JavaScriptUtils.containsScript(gsp.getLon()))
					{
						lonValue = (String)getValueFromScript(gsp.getLon(), null, null);
					}
					else
					{
						lonValue = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getLon(), field);
					}
					
					if (latValue != null && lonValue != null)
					{
						dLat = Double.parseDouble(latValue);
						dLon = Double.parseDouble(lonValue);
					}
				}
				
				else
				{
					String city, region, country, countryCode = null;
					
					// Create a GeoReferencePojo from the GeoSpec object
					GeoFeaturePojo gfp = new GeoFeaturePojo();

					if (gsp.getCity() != null)
					{
						if (JavaScriptUtils.containsScript(gsp.getCity()))
						{
							city = (String)getValueFromScript(gsp.getCity(), null, null);
						}
						else
						{
							city = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getCity(), null);
						}

						gfp.setCity(city);
						gfp.setSearch_field(city);
					}

					if (gsp.getStateProvince() != null)
					{
						if (JavaScriptUtils.containsScript(gsp.getStateProvince()))
						{
							region = (String)getValueFromScript(gsp.getStateProvince(), null, null);
						}
						else
						{
							region = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getStateProvince(), null);
						}

						gfp.setRegion(region);
						if (gfp.getSearch_field() == null) gfp.setSearch_field(region);
					}

					if (gsp.getCountry() != null)
					{
						if (JavaScriptUtils.containsScript(gsp.getCountry()))
						{
							country = (String)getValueFromScript(gsp.getCountry(), null, null);
						}
						else
						{
							country = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getCountry(), null);
						}

						gfp.setCountry(country);
						if (gfp.getSearch_field() == null) gfp.setSearch_field(country);
					}

					if (gsp.getCountryCode() != null)
					{
						if (JavaScriptUtils.containsScript(gsp.getCountryCode()))
						{
							countryCode = (String)getValueFromScript(gsp.getCountryCode(), null, null);
						}
						else
						{
							countryCode = FieldExtractor.getFormattedTextFromField(dpOrIt,gsp.getCountryCode(), null);
						}

						gfp.setCountry_code(countryCode);
						// (Don't set to search field for country code - it will be equal to country...)
					}

					// Send the GeoReferencePojo to enrichGeoInfo to attempt to get lat and lon values
					boolean bStrictMatch = (null == gsp.getStrictMatch()) || gsp.getStrictMatch();
					List<GeoFeaturePojo> gList = GeoReference.enrichGeoInfo(gfp, bStrictMatch, true, 1);
					GeoFeaturePojo firstGeo = gList.get(0);
					latValue = firstGeo.getGeoindex().lat.toString();
					lonValue = firstGeo.getGeoindex().lon.toString();
					gsp.setOntology_type(firstGeo.getOntology_type());
					
					// Set lat and long in DocGeo if possible
					dLat = Double.parseDouble(latValue);
					dLon = Double.parseDouble(lonValue);
				}
			}
			else if (null != f.getDocGeo()) {
				dLat = f.getDocGeo().lat;
				dLon = f.getDocGeo().lon;
			}

			if (dLat != 0 && dLon !=0)
			{
				g = new GeoPojo();
				g.lat = dLat;
				g.lon = dLon;
			}

			
			return g;
		}
		catch (Exception e) // If alternatives are specified we can try them instead
		{
			if (null != gsp.getAlternatives()) {
				for (GeoSpecPojo altIn: gsp.getAlternatives()) {
					GeoPojo altOut =  getEntityGeo(altIn, f, field);
					if (null != altOut) {
						gsp.setOntology_type(altIn.getOntology_type());
						return altOut;
					}
				}
			}			
			return null;
		}
	}
	
	
	
	/**
	 * executeEntityAssociationValidation
	 * @param script
	 * @param j
	 * @return
	 */
	private Boolean executeEntityAssociationValidation(String script, String value, String index)
	{
		Boolean retVal = false;
		try
		{
			// Run our script that checks whether or not the entity/association should be added
			Object retValObj = getValueFromScript(script, value, index);
			if(retValObj!=null){
				retVal = (Boolean) retValObj;
			}
		}
		catch (Exception e) 
		{
			_context.getHarvestStatus().logMessage(HarvestExceptionUtils.createExceptionMessage(e).toString(), true);
		}
		return retVal;
	}
	

	/////////////////////////////////////////////////////
	
	// Utility function to expand all "iterateOver"s of the format a.b.c 

	private static void expandIterationLoops(StructuredAnalysisConfigPojo s) {

		// Entities first:
		HashMap<String, EntitySpecPojo> nestedEntityMap = null;
		ArrayList<EntitySpecPojo> newEntityEntries = null;
		
		if (null != s.getEntities()) {	
			Iterator<EntitySpecPojo> entSpecIt = s.getEntities().iterator();
			while (entSpecIt.hasNext()) {
				EntitySpecPojo entS = entSpecIt.next(); 
				if ((null != entS.getIterateOver()) && (entS.getIterateOver().contains(".")))
				{ 
					// For associations only: included here so it doesn't get forgotten in cut-and-pastes...
					//if (assocS.getIterateOver().contains(",") || assocS.getIterateOver().contains("/")) {
					//	continue;
					//}
					if (null == nestedEntityMap) { // (do need this map)
						nestedEntityMap = new HashMap<String, EntitySpecPojo>();
					}
					if (null == newEntityEntries) {
						newEntityEntries = new ArrayList<EntitySpecPojo>(10);						
					}
					EntitySpecPojo prevLevelSpec = null;					
					String iterateOver = entS.getIterateOver() + "."; // (end with "." to make life easier) 
					entS.setIterateOver(null); // (this is now the end of the chain)
					entSpecIt.remove(); // (so remove from the list)
					
					boolean bChainBroken = false;
					for (int nCurrDot = iterateOver.indexOf('.'), nLastDot = -1; 
							nCurrDot >= 0; 
						nLastDot = nCurrDot, nCurrDot = iterateOver.indexOf('.', nCurrDot + 1))
					{						
						String currLevel = iterateOver.substring(0, nCurrDot); // (eg a, a.b, a.b.c)
						String lastComp_currLevel = iterateOver.substring(nLastDot + 1, nCurrDot); // (eg a, b, c)
						
						EntitySpecPojo currLevelSpec = null;
						if (!bChainBroken) {
							currLevelSpec = nestedEntityMap.get(currLevel);
						}
						if (null == currLevelSpec) {
							bChainBroken = true; // (no point in doing any more lookups)
							currLevelSpec = new EntitySpecPojo();
							nestedEntityMap.put(currLevel, currLevelSpec);
							currLevelSpec.setIterateOver(lastComp_currLevel); 
							if (null != prevLevelSpec) { // add myself to the next level
								if (null == prevLevelSpec.getEntities()) {
									prevLevelSpec.setEntities(new ArrayList<EntitySpecPojo>(5));
								}
								prevLevelSpec.getEntities().add(currLevelSpec);								
							}
							else { // I am the first level, add myself to entity list
								newEntityEntries.add(currLevelSpec); // (this is now the head of the chain)													
							}
							prevLevelSpec = currLevelSpec;
						}//TESTED
						else { // We're already have this level, so carry on:
							prevLevelSpec = currLevelSpec; //(in case this was the last level...)
							continue;
						}//TESTED
						
					} //(end loop over expansion levels)

					// Add entS (ie the spec with the content) to the end of the chain
					
					if (null != prevLevelSpec) { // (probably an internal logic error if not)
						if (null == prevLevelSpec.getEntities()) {
							prevLevelSpec.setEntities(new ArrayList<EntitySpecPojo>(5));
						}
						prevLevelSpec.getEntities().add(entS);
					}//TESTED					
					
				}//(end found entity with expandable iterateOver)
				else if (null != entS.getIterateOver()) { // Non-nested case, simpler 
					// For associations only: included here so it doesn't get forgotten in cut-and-pastes...
					//if (assocS.getIterateOver().contains(",") || assocS.getIterateOver().contains("/")) {
					//	continue;
					//}
					if (null == nestedEntityMap) { // (do need this map)
						nestedEntityMap = new HashMap<String, EntitySpecPojo>();
					}					
					//(and logic is different enough that it makes most sense to do separately rather than grovel to save a few lines)
					
					EntitySpecPojo currSpec = nestedEntityMap.get(entS.getIterateOver());
					if (null != currSpec) {
						entSpecIt.remove();
						if (null == currSpec.getEntities()) {
							currSpec.setEntities(new ArrayList<EntitySpecPojo>(5));
						}
						entS.setIterateOver(null);
						currSpec.getEntities().add(entS);						
					}
					else {
						nestedEntityMap.put(entS.getIterateOver(), entS);
					}
				}//TESTED
			}// (end loop over entities)
			
			if (null != newEntityEntries) {
				s.getEntities().addAll(newEntityEntries);
			}			
			
		}//(end if entities)
				
		// Identical code for associations:
		// Just going to cut and replace and rename a few variables
		//HashMap<String, AssociationSpecPojo> nestedAssociationMap = null;
		
		HashMap<String, AssociationSpecPojo> nestedAssocMap = null;
		ArrayList<AssociationSpecPojo> newAssocEntries = null;
		
		if (null != s.getAssociations()) {	
			Iterator<AssociationSpecPojo> assocSpecIt = s.getAssociations().iterator();
			while (assocSpecIt.hasNext()) {
				AssociationSpecPojo assocS = assocSpecIt.next(); 
				if ((null != assocS.getIterateOver()) && (assocS.getIterateOver().contains(".")))
				{ 
					// For associations only: included here so it doesn't get forgotten in cut-and-pastes...
					if (assocS.getIterateOver().contains(",") || assocS.getIterateOver().contains("/")) {
						continue;
					}//TESTED
					if (null == nestedAssocMap) { // (do need this map)
						nestedAssocMap = new HashMap<String, AssociationSpecPojo>();
					}
					if (null == newAssocEntries) {
						newAssocEntries = new ArrayList<AssociationSpecPojo>(10);						
					}
					AssociationSpecPojo prevLevelSpec = null;					
					String iterateOver = assocS.getIterateOver() + "."; // (end with "." to make life easier) 
					assocS.setIterateOver(null); // (this is now the end of the chain)
					assocSpecIt.remove(); // (so remove from the list)
					
					boolean bChainBroken = false;
					for (int nCurrDot = iterateOver.indexOf('.'), nLastDot = -1; 
							nCurrDot >= 0; 
						nLastDot = nCurrDot, nCurrDot = iterateOver.indexOf('.', nCurrDot + 1))
					{						
						String currLevel = iterateOver.substring(0, nCurrDot); // (eg a, a.b, a.b.c)
						String lastComp_currLevel = iterateOver.substring(nLastDot + 1, nCurrDot); // (eg a, b, c)
						
						AssociationSpecPojo currLevelSpec = null;
						if (!bChainBroken) {
							currLevelSpec = nestedAssocMap.get(currLevel);
						}
						if (null == currLevelSpec) {
							bChainBroken = true; // (no point in doing any more lookups)
							currLevelSpec = new AssociationSpecPojo();
							nestedAssocMap.put(currLevel, currLevelSpec);
							currLevelSpec.setIterateOver(lastComp_currLevel); 
							if (null != prevLevelSpec) { // add myself to the next level
								if (null == prevLevelSpec.getAssociations()) {
									prevLevelSpec.setAssociations(new ArrayList<AssociationSpecPojo>(5));
								}
								prevLevelSpec.getAssociations().add(currLevelSpec);								
							}
							else { // I am the first level, add myself to entity list
								newAssocEntries.add(currLevelSpec); // (this is now the head of the chain)													
							}
							prevLevelSpec = currLevelSpec;
						}//TESTED
						else { // We're already have this level, so carry on:
							prevLevelSpec = currLevelSpec; //(in case this was the last level...)
							continue;
						}//TESTED
						
					} //(end loop over expansion levels)

					// Add entS (ie the spec with the content) to the end of the chain
					
					if (null != prevLevelSpec) { // (probably an internal logic error if not)
						if (null == prevLevelSpec.getAssociations()) {
							prevLevelSpec.setAssociations(new ArrayList<AssociationSpecPojo>(5));
						}
						prevLevelSpec.getAssociations().add(assocS);
					}//TESTED					
					
				}//(end found entity with expandable iterateOver)
				else if (null != assocS.getIterateOver()) { // Non-nested case, simpler 
					// For associations only: included here so it doesn't get forgotten in cut-and-pastes...
					if (assocS.getIterateOver().contains(",") || assocS.getIterateOver().contains("/")) {
						continue;
					}//TESTED
					if (null == nestedAssocMap) { // (do need this map)
						nestedAssocMap = new HashMap<String, AssociationSpecPojo>();
					}					
					//(and logic is different enough that it makes most sense to do separately rather than grovel to save a few lines)
					
					AssociationSpecPojo currSpec = nestedAssocMap.get(assocS.getIterateOver());
					if (null != currSpec) {
						assocSpecIt.remove();
						if (null == currSpec.getAssociations()) {
							currSpec.setAssociations(new ArrayList<AssociationSpecPojo>(5));
						}
						assocS.setIterateOver(null);
						currSpec.getAssociations().add(assocS);						
					}
					else {
						nestedAssocMap.put(assocS.getIterateOver(), assocS);
					}
				}//TESTED
			}// (end loop over entities)
			
			if (null != newAssocEntries) {
				s.getAssociations().addAll(newAssocEntries);
			}			
			
		}//(end if entities)
	}

	/////////////////////////////////////////////////////////////////////////////
	
	// Share utility to repopulate the entity cache before ent/assoc processing
	
	private void repopulateEntityCacheIfNeeded(DocumentPojo f)
	{
		if (null == _entityMap) {
			_entityMap = new HashSet<String>();
			_geoMap = new HashMap<String, GeoPojo>();
			if (f.getEntities() != null) 
			{
				for (EntityPojo ent: f.getEntities()) {
					if (null != ent.getIndex()) {
						_entityMap.add(ent.getIndex());
						if (null != ent.getGeotag()) {
							_geoMap.put(ent.getIndex(), ent.getGeotag());
						}
					}
				}
			}//TESTED (in INF_1360_test_source.json:test8, hand created f.entities containing "entity2/type2")
		}			
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////

	public static String createAssocSignature(AssociationPojo assoc) {
		StringBuffer sb = new StringBuffer();
		if (null != assoc.getEntity1_index()) sb.append(assoc.getEntity1_index());
		sb.append("|");
		if (null != assoc.getEntity2_index()) sb.append(assoc.getEntity2_index());
		sb.append("|");
		if (null != assoc.getGeo_index()) sb.append(assoc.getGeo_index());
		sb.append("|");
		if (null != assoc.getVerb_category()) sb.append(assoc.getVerb_category());
		
		return sb.toString();
	}

	private ScriptContext fillScriptContext(String value, String index) {
		ScriptContext scriptContext = null;
	
		// Create script object from entity or association JSON
		scriptContext = compiledScriptFactory.getScriptContext();
		if (_iterator != null)
		{
			Object existingIteratorPojo = compiledScriptFactory.getScriptContext().getAttribute("iteratorPojo");
			if(existingIteratorPojo!=_iterator){
				compiledScriptFactory.executeCompiledScript(JavaScriptUtils.getIteratorOm2js(),"iteratorPojo", _iterator);
				//logger.debug("iterator "+_iteratorO+"  OM mapped into JS.");
			}else{
				//logger.debug("iterator "+_iteratorO+" was already OM mapped into JS.");
			}
		}
		else {
			scriptContext.setAttribute("iteratorPojo", null,ScriptContext.ENGINE_SCOPE);
		}
		
		// Pass value into script as _value so it is accessible
    	scriptContext.setAttribute("_value", value,ScriptContext.ENGINE_SCOPE); 
		
		//
		if (index != null) { 
			scriptContext.setAttribute("_index", index,ScriptContext.ENGINE_SCOPE);  
		}
		else if (_iteratorIndex != null) { 
			scriptContext.setAttribute("_index", _iteratorIndex,ScriptContext.ENGINE_SCOPE); ; 
		}
		else {
			scriptContext.setAttribute("_index", null,ScriptContext.ENGINE_SCOPE);  				
		}
		return scriptContext;
	}
	
	/////////////////////////////////////////////////////////////////////////////
	
	//TEST CODE:
	
	public static void main(String[] argv) {
		
		// Test entity expansion:
		
		StructuredAnalysisConfigPojo s = new StructuredAnalysisConfigPojo();
		s.setEntities(new ArrayList<EntitySpecPojo>(20));
		EntitySpecPojo e = null;
		e = new EntitySpecPojo();
		//a1
		e.setIterateOver("a");
		e.setDisambiguated_name("a.test1");
		s.getEntities().add(e);
		//a2
		e = new EntitySpecPojo();
		e.setIterateOver("a");
		e.setDisambiguated_name("a.test2");
		s.getEntities().add(e);
		//x1
		e = new EntitySpecPojo();
		e.setIterateOver("x");
		e.setDisambiguated_name("x.test1");
		s.getEntities().add(e);
		//a.b1
		e = new EntitySpecPojo();
		e.setIterateOver("a.b");
		e.setDisambiguated_name("a.b.test1");
		s.getEntities().add(e);
		//a.b.c.d1
		e = new EntitySpecPojo();
		e.setIterateOver("a.b.c.d");
		e.setDisambiguated_name("a.b.c.d.test1");
		s.getEntities().add(e);
		//a.b2
		e = new EntitySpecPojo();
		e.setIterateOver("a.b");
		e.setDisambiguated_name("a.b.test2");
		s.getEntities().add(e);
		//p.q1
		e = new EntitySpecPojo();
		e.setIterateOver("p.q");
		e.setDisambiguated_name("p.q.test1");
		s.getEntities().add(e);
		// null case
		e = new EntitySpecPojo();
		e.setDisambiguated_name("(null iterator)");
		s.getEntities().add(e);
		
		expandIterationLoops(s);
		
		System.out.println("TEST1: ENTITY ITERATION EXPANSION: ");
		System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(s));
		
		s.setAssociations(new ArrayList<AssociationSpecPojo>(20));
		AssociationSpecPojo assoc = null;
		assoc = new AssociationSpecPojo();
		//a1
		assoc.setIterateOver("a");
		assoc.setEntity1("a.test1");
		s.getAssociations().add(assoc);
		//a2
		assoc = new AssociationSpecPojo();
		assoc.setIterateOver("a");
		assoc.setEntity1("a.test2");
		s.getAssociations().add(assoc);
		//x1
		assoc = new AssociationSpecPojo();
		assoc.setIterateOver("x");
		assoc.setEntity1("x.test1");
		s.getAssociations().add(assoc);
		//a.b1
		assoc = new AssociationSpecPojo();
		assoc.setIterateOver("a.b");
		assoc.setEntity1("a.b.test1");
		s.getAssociations().add(assoc);
		//a.b.c.d1
		assoc =new AssociationSpecPojo();
		assoc.setIterateOver("a.b.c.d");
		assoc.setEntity1("a.b.c.d.test1");
		s.getAssociations().add(assoc);
		//a.b2
		assoc =new AssociationSpecPojo();
		assoc.setIterateOver("a.b");
		assoc.setEntity1("a.b.test2");
		s.getAssociations().add(assoc);
		//p.q1
		assoc =new AssociationSpecPojo();
		assoc.setIterateOver("p.q");
		assoc.setEntity1("p.q.test1");
		s.getAssociations().add(assoc);
		//"," case
		assoc =new AssociationSpecPojo();
		assoc.setIterateOver("p.q,RR");
		assoc.setEntity1("ITERATE OVER p.q,RR");
		s.getAssociations().add(assoc);
		//"/" case
		assoc =new AssociationSpecPojo();
		assoc.setIterateOver("p.q/SS");
		assoc.setEntity1("ITERATE OVER p.q/SS");
		s.getAssociations().add(assoc);
		// null case
		assoc =new AssociationSpecPojo();
		assoc.setEntity1("(null iterator)");
		s.getAssociations().add(assoc);
		
		//SHOULD HAVE TEST FOR ITERATE OVER p,q (now hand tested anyway)
		
		expandIterationLoops(s);
		
		System.out.println("TEST2: ASSOCIATION ITERATION EXPANSION: ");
		System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(s));
	}
	
	
	
}

