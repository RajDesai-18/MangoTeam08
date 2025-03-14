/*
    Mango - Open Source M2M - http://mango.serotoninsoftware.com
    Copyright (C) 2006-2011 Serotonin Software Technologies Inc.
    @author Matthew Lohbihler
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.mango.vo;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.mango.rt.dataImage.SetPointSource;

/**
 * @author Matthew Lohbihler
 */
public class AnonymousUser implements SetPointSource {
    public int getSetPointSourceId() {
        return 0;
    }

//  Code Smell 2: Speculative Generality - (Dispensables)
    public SetPointSource.Type getSetPointSourceType() { 
        return SetPointSource.Type.USER;
    }

    @Override
    public void raiseRecursionFailureEvent() {
        throw new ShouldNeverHappenException("");
    }
}
