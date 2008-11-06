/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.core.configuration.xml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.flow.support.StateTransition;
import org.springframework.batch.core.job.flow.support.state.EndState;
import org.springframework.batch.core.job.flow.support.state.StepState;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

/**
 * Internal parser for the &lt;step/&gt; elements inside a job. A step element
 * references a bean definition for a {@link Step} and goes on to (optionally)
 * list a set of transitions from that step to others with &lt;next on="pattern"
 * to="stepName"/&gt;. Used by the {@link JobParser}.
 * 
 * @see JobParser
 * 
 * @author Dave Syer
 * @author Thomas Risberg
 * 
 */
public class StepParser {

	// For generating unique state names for end transitions
	private static int endCounter = 0;

	/**
	 * Parse the step and turn it into a list of transitions.
	 * 
	 * @param element the &lt;step/gt; element to parse
	 * @param parserContext the parser context for the bean factory
	 * @return a collection of bean definitions for {@link StateTransition}
	 * instances objects
	 */
	public Collection<RuntimeBeanReference> parse(Element element, ParserContext parserContext) {

		BeanDefinitionBuilder stateBuilder = BeanDefinitionBuilder.genericBeanDefinition(StepState.class);
		String stepRef = element.getAttribute("name");

		@SuppressWarnings("unchecked")
		List<Element> taskElements = (List<Element>) DomUtils.getChildElementsByTagName(element, "task");
		@SuppressWarnings("unchecked")
		List<Element> chunkOrientedElements = (List<Element>) DomUtils.getChildElementsByTagName(element, "chunk-oriented");
		if (taskElements.size() > 0) {
			Object task = parseTask(taskElements.get(0), parserContext);
			stateBuilder.addConstructorArgValue(stepRef);
			stateBuilder.addConstructorArgValue(task);
		}
		else if (chunkOrientedElements.size() > 0) {
			Object task = parseChunkOriented(chunkOrientedElements.get(0), parserContext);
			stateBuilder.addConstructorArgValue(stepRef);
			stateBuilder.addConstructorArgValue(task);
		}
		else if (StringUtils.hasText(stepRef)) {
				RuntimeBeanReference stateDef = new RuntimeBeanReference(stepRef);
				stateBuilder.addConstructorArgValue(stateDef);
		}
		else {
			throw new BeanCreationException("Error creating Step for " + element);
		}
		return getNextElements(parserContext, stateBuilder.getBeanDefinition(), element);

	}

	/**
	 * @param parserContext
	 * @param stateDef
	 * @param element
	 * @return a collection of {@link StateTransition} references
	 */
	public static Collection<RuntimeBeanReference> getNextElements(ParserContext parserContext,
			BeanDefinition stateDef, Element element) {

		Collection<RuntimeBeanReference> list = new ArrayList<RuntimeBeanReference>();

		String shortNextAttribute = element.getAttribute("next");
		boolean hasNextAttribute = StringUtils.hasText(shortNextAttribute);
		if (hasNextAttribute) {
			list.add(getStateTransitionReference(parserContext, stateDef, null, shortNextAttribute));
		}

		@SuppressWarnings("unchecked")
		List<Element> nextElements = (List<Element>) DomUtils.getChildElementsByTagName(element, "next");
		@SuppressWarnings("unchecked")
		List<Element> stopElements = (List<Element>) DomUtils.getChildElementsByTagName(element, "stop");
		nextElements.addAll(stopElements);
		@SuppressWarnings("unchecked")
		List<Element> endElements = (List<Element>) DomUtils.getChildElementsByTagName(element, "end");
		nextElements.addAll(endElements);

		for (Element nextElement : nextElements) {
			String onAttribute = nextElement.getAttribute("on");
			String nextAttribute = nextElement.getAttribute("to");
			if (hasNextAttribute && onAttribute.equals("*")) {
				throw new BeanCreationException("Duplicate transition pattern found for '*' "
						+ "(only specify one of next= attribute at step level and next element with on='*')");
			}

			String name = nextElement.getNodeName();
			if ("stop".equals(name) || "end".equals(name)) {

				String statusName = nextElement.getAttribute("status");
				BatchStatus status = StringUtils.hasText(statusName) ? BatchStatus.valueOf(statusName)
						: BatchStatus.STOPPED;
				String nextOnEnd = StringUtils.hasText(statusName) ? null : nextAttribute;

				BeanDefinitionBuilder endBuilder = BeanDefinitionBuilder.genericBeanDefinition(EndState.class);
				endBuilder.addConstructorArgValue(status);
				String endName = "end" + endCounter;
				endCounter++;

				endBuilder.addConstructorArgValue(endName);
				list.add(getStateTransitionReference(parserContext, endBuilder.getBeanDefinition(), onAttribute, nextOnEnd));
				nextAttribute = endName;
	
			}
			list.add(getStateTransitionReference(parserContext, stateDef, onAttribute, nextAttribute));
		}

		if (list.isEmpty() && !hasNextAttribute) {
			list.add(getStateTransitionReference(parserContext, stateDef, null, null));
		}

		return list;
	}

	/**
	 * @param parserContext the parser context
	 * @param stateDefinition a reference to the state implementation
	 * @param on the pattern value
	 * @param next the next step id
	 * @return a bean definition for a {@link StateTransition}
	 */
	public static RuntimeBeanReference getStateTransitionReference(ParserContext parserContext,
			BeanDefinition stateDefinition, String on, String next) {

		BeanDefinitionBuilder nextBuilder = BeanDefinitionBuilder.genericBeanDefinition(StateTransition.class);
		nextBuilder.addConstructorArgValue(stateDefinition);

		if (StringUtils.hasText(on)) {
			nextBuilder.addConstructorArgValue(on);
		}

		if (StringUtils.hasText(next)) {
			nextBuilder.setFactoryMethod("createStateTransition");
			nextBuilder.addConstructorArgValue(next);
		}
		else {
			nextBuilder.setFactoryMethod("createEndStateTransition");
		}

		// TODO: do we need to use RuntimeBeanReference?
		AbstractBeanDefinition nextDef = nextBuilder.getBeanDefinition();
		String nextDefName = parserContext.getReaderContext().generateBeanName(nextDef);
		BeanComponentDefinition nextDefComponent = new BeanComponentDefinition(nextDef, nextDefName);
		parserContext.registerBeanComponent(nextDefComponent);

		return new RuntimeBeanReference(nextDefName);

	}

	/**
	 * @param element
	 * @param parserContext
	 * @return the TaskletStep bean
	 */
	protected RootBeanDefinition parseChunkOriented(Element element, ParserContext parserContext) {
		
		System.out.println("PARSING PROCESS!!!");
		
    	RootBeanDefinition bd = new RootBeanDefinition("org.springframework.batch.core.step.item.SimpleStepFactoryBean", null, null);
		
        String readerBeanId = element.getAttribute("reader");
        if (StringUtils.hasText(readerBeanId)) {
            RuntimeBeanReference readerRef = new RuntimeBeanReference(readerBeanId);
            bd.getPropertyValues().addPropertyValue("itemReader", readerRef);
        }

        String processorBeanId = element.getAttribute("processor");
        if (StringUtils.hasText(processorBeanId)) {
            RuntimeBeanReference processorRef = new RuntimeBeanReference(processorBeanId);
            bd.getPropertyValues().addPropertyValue("itemProcessor", processorRef);
        }

        String writerBeanId = element.getAttribute("writer");
        if (StringUtils.hasText(writerBeanId)) {
            RuntimeBeanReference writerRef = new RuntimeBeanReference(writerBeanId);
            bd.getPropertyValues().addPropertyValue("itemWriter", writerRef);
        }

        String jobRepository = element.getAttribute("job-repository");
        RuntimeBeanReference jobRepositoryRef = new RuntimeBeanReference(jobRepository);
        bd.getPropertyValues().addPropertyValue("jobRepository", jobRepositoryRef);

        String transactionManager = element.getAttribute("transaction-manager");
        RuntimeBeanReference tx = new RuntimeBeanReference(transactionManager);
        bd.getPropertyValues().addPropertyValue("transactionManager", tx);
		
        bd.setRole(BeanDefinition.ROLE_SUPPORT);
        
        return bd;

	}

	/**
	 * @param element
	 * @param parserContext
	 * @return the TaskletStep bean
	 */
	protected RootBeanDefinition parseTask(Element element, ParserContext parserContext) {

    	RootBeanDefinition bd = new RootBeanDefinition("org.springframework.batch.core.step.tasklet.TaskletStep", null, null);

        String taskletBeanId = element.getAttribute("tasklet");
        if (StringUtils.hasText(taskletBeanId)) {
            RuntimeBeanReference taskletRef = new RuntimeBeanReference(taskletBeanId);
            bd.getPropertyValues().addPropertyValue("tasklet", taskletRef);
        }

        String jobRepository = element.getAttribute("job-repository");
        RuntimeBeanReference jobRepositoryRef = new RuntimeBeanReference(jobRepository);
        bd.getPropertyValues().addPropertyValue("jobRepository", jobRepositoryRef);

        String transactionManager = element.getAttribute("transaction-manager");
        RuntimeBeanReference tx = new RuntimeBeanReference(transactionManager);
        bd.getPropertyValues().addPropertyValue("transactionManager", tx);
		
        bd.setRole(BeanDefinition.ROLE_SUPPORT);
        
        return bd;

    }

}
