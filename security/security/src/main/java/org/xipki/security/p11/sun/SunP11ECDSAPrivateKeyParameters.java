/*
 * Copyright (c) 2014 Lijun Liao
 *
 * TO-BE-DEFINE
 *
 */

package org.xipki.security.p11.sun;

import java.security.PrivateKey;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

/**
 * @author Lijun Liao
 */

public class SunP11ECDSAPrivateKeyParameters extends AsymmetricKeyParameter
{
    private PrivateKey privateKey;

    public SunP11ECDSAPrivateKeyParameters(PrivateKey privateKey)
    {
        super(true);
        this.privateKey = privateKey;
    }

    public PrivateKey getPrivateKey()
    {
        return privateKey;
    }
}