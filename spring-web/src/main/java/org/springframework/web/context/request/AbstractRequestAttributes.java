/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.context.request;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.Assert;

/**
 * Abstract support class for RequestAttributes implementations,
 * offering a request completion mechanism for request-specific destruction
 * callbacks and for updating accessed session attributes.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #requestCompleted()
 */
public abstract class AbstractRequestAttributes implements RequestAttributes {

	/** Map from attribute name String to destruction callback Runnable. */
	protected final Map<String, Runnable> requestDestructionCallbacks = new LinkedHashMap<>(8);

	private volatile boolean requestActive = true;


	/**
	 * Signal that the request has been completed.
	 * 发出请求以及完成的信号
	 * <p>Executes all request destruction callbacks and updates the
	 * session attributes that have been accessed during request processing.
	 */
	public void requestCompleted() {
		//执行注册的请求回调方法，在请求完成的时候
		executeRequestDestructionCallbacks();
		//更新以访问的会话属性
		updateAccessedSessionAttributes();
		//将请求活跃标识设为false
		this.requestActive = false;
	}

	/**
	 * Determine whether the original request is still active.
	 * @see #requestCompleted()
	 */
	protected final boolean isRequestActive() {
		return this.requestActive;
	}

	/**
	 * Register the given callback as to be executed after request completion.
	 * @param name the name of the attribute to register the callback for
	 * @param callback the callback to be executed for destruction
	 */
	protected final void registerRequestDestructionCallback(String name, Runnable callback) {
		Assert.notNull(name, "Name must not be null");
		Assert.notNull(callback, "Callback must not be null");
		synchronized (this.requestDestructionCallbacks) {
			this.requestDestructionCallbacks.put(name, callback);
		}
	}

	/**
	 * Remove the request destruction callback for the specified attribute, if any.
	 * @param name the name of the attribute to remove the callback for
	 */
	protected final void removeRequestDestructionCallback(String name) {
		Assert.notNull(name, "Name must not be null");
		synchronized (this.requestDestructionCallbacks) {
			this.requestDestructionCallbacks.remove(name);
		}
	}

	/**
	 * Execute all callbacks that have been registered for execution
	 * after request completion.
	 * 在执行请求完成后，执行已经注册的所有回调
	 */
	private void executeRequestDestructionCallbacks() {
		//对已经注册的全部回调方法，加锁，迭代执行；执行完之后清空容器
		synchronized (this.requestDestructionCallbacks) {
			for (Runnable runnable : this.requestDestructionCallbacks.values()) {
				runnable.run();
			}
			this.requestDestructionCallbacks.clear();
		}
	}

	/**
	 * Update all session attributes that have been accessed during request processing,
	 * to expose their potentially updated state to the underlying session manager.
	 */
	protected abstract void updateAccessedSessionAttributes();

}
