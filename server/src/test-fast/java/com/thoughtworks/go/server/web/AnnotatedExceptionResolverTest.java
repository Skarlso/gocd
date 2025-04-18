/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.web;

import com.thoughtworks.go.server.util.ErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AnnotatedExceptionResolverTest {
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final ModelAndView mov = new ModelAndView();

    @BeforeEach
    public void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    public void testShouldInvokeMethodAnnotatedAndReturnModelAndView() {
        ControllerFake controllerFake = new ControllerFake();
        ModelAndView modelAndView =
                new AnnotatedExceptionResolver().resolveException(request, response, controllerFake, null);
        assertTrue(controllerFake.isInvoked(), "the method should be invoked");
        assertEquals(mov, modelAndView);
    }

    public class ControllerFake {
        private boolean invoked = false;

        public boolean isInvoked() {
            return this.invoked;
        }

        @ErrorHandler
        public ModelAndView anyMethod(HttpServletRequest req, HttpServletResponse resp, Exception e) {
            invoked = true;
            return mov;
        }
    }

}

