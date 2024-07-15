// ASM: a very small and fast Java bytecode manipulation framework
// Copyright (c) 2000-2011 INRIA, France Telecom
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. Neither the name of the copyright holders nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
// THE POSSIBILITY OF SUCH DAMAGE.
package org.springframework.asm;

/**
 * A {@link MethodVisitor} that generates a corresponding 'method_info' structure, as defined in the
 * Java Virtual Machine Specification (JVMS).
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.6">JVMS
 *     4.6</a>
 * @author Eric Bruneton
 * @author Eugene Kuleshov
 */
final class MethodWriter extends MethodVisitor {

  /** Indicates that nothing must be computed. */
  static final int COMPUTE_NOTHING = 0;

  /**
   * Indicates that the maximum stack size and the maximum number of local variables must be
   * computed, from scratch.
   */
  // 表示需要计算最大栈深度和最大布局变量数量
  static final int COMPUTE_MAX_STACK_AND_LOCAL = 1;

  /**
   * Indicates that the maximum stack size and the maximum number of local variables must be
   * computed, from the existing stack map frames. This can be done more efficiently than with the
   * control flow graph algorithm used for {@link #COMPUTE_MAX_STACK_AND_LOCAL}, by using a linear
   * scan of the bytecode instructions.
   */
  // 表示需要从存在的stack map frame中计算出最大栈深度和最大局部变量数量，这种算法比线性扫描字节码更高效
  static final int COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES = 2;

  /**
   * Indicates that the stack map frames of type F_INSERT must be computed. The other frames are not
   * computed. They should all be of type F_NEW and should be sufficient to compute the content of
   * the F_INSERT frames, together with the bytecode instructions between a F_NEW and a F_INSERT
   * frame - and without any knowledge of the type hierarchy (by definition of F_INSERT).
   */
  // 表示F_INSERT类型的stack map frame需要被计算，其他frame不需要
  static final int COMPUTE_INSERTED_FRAMES = 3;

  /**
   * Indicates that all the stack map frames must be computed. In this case the maximum stack size
   * and the maximum number of local variables is also computed.
   */
  // 表示所有的stack map frame都需要被计算，在这种情况下最大栈深度 和 最大局部变量数量也同样被计算了
  static final int COMPUTE_ALL_FRAMES = 4;

  /** Indicates that {@link #STACK_SIZE_DELTA} is not applicable (not constant or never used). */
  private static final int NA = 0;

  /**
   * The stack size variation corresponding to each JVM opcode. The stack size variation for opcode
   * 'o' is given by the array element at index 'o'.
   *
   * @see <a href="https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-6.html">JVMS 6</a>
   */
  // 该数组是所有字节码对应的栈slot的变化量
  private static final int[] STACK_SIZE_DELTA = {
    0, // nop = 0 (0x0)
    1, // aconst_null = 1 (0x1)
    1, // iconst_m1 = 2 (0x2)
    1, // iconst_0 = 3 (0x3)
    1, // iconst_1 = 4 (0x4)
    1, // iconst_2 = 5 (0x5)
    1, // iconst_3 = 6 (0x6)
    1, // iconst_4 = 7 (0x7)
    1, // iconst_5 = 8 (0x8)
    2, // lconst_0 = 9 (0x9)
    2, // lconst_1 = 10 (0xa)
    1, // fconst_0 = 11 (0xb)
    1, // fconst_1 = 12 (0xc)
    1, // fconst_2 = 13 (0xd)
    2, // dconst_0 = 14 (0xe)
    2, // dconst_1 = 15 (0xf)
    1, // bipush = 16 (0x10)
    1, // sipush = 17 (0x11)
    1, // ldc = 18 (0x12)
    NA, // ldc_w = 19 (0x13)
    NA, // ldc2_w = 20 (0x14)
    1, // iload = 21 (0x15)
    2, // lload = 22 (0x16)
    1, // fload = 23 (0x17)
    2, // dload = 24 (0x18)
    1, // aload = 25 (0x19)
    NA, // iload_0 = 26 (0x1a)
    NA, // iload_1 = 27 (0x1b)
    NA, // iload_2 = 28 (0x1c)
    NA, // iload_3 = 29 (0x1d)
    NA, // lload_0 = 30 (0x1e)
    NA, // lload_1 = 31 (0x1f)
    NA, // lload_2 = 32 (0x20)
    NA, // lload_3 = 33 (0x21)
    NA, // fload_0 = 34 (0x22)
    NA, // fload_1 = 35 (0x23)
    NA, // fload_2 = 36 (0x24)
    NA, // fload_3 = 37 (0x25)
    NA, // dload_0 = 38 (0x26)
    NA, // dload_1 = 39 (0x27)
    NA, // dload_2 = 40 (0x28)
    NA, // dload_3 = 41 (0x29)
    NA, // aload_0 = 42 (0x2a)
    NA, // aload_1 = 43 (0x2b)
    NA, // aload_2 = 44 (0x2c)
    NA, // aload_3 = 45 (0x2d)
    -1, // iaload = 46 (0x2e)
    0, // laload = 47 (0x2f)
    -1, // faload = 48 (0x30)
    0, // daload = 49 (0x31)
    -1, // aaload = 50 (0x32)
    -1, // baload = 51 (0x33)
    -1, // caload = 52 (0x34)
    -1, // saload = 53 (0x35)
    -1, // istore = 54 (0x36)
    -2, // lstore = 55 (0x37)
    -1, // fstore = 56 (0x38)
    -2, // dstore = 57 (0x39)
    -1, // astore = 58 (0x3a)
    NA, // istore_0 = 59 (0x3b)
    NA, // istore_1 = 60 (0x3c)
    NA, // istore_2 = 61 (0x3d)
    NA, // istore_3 = 62 (0x3e)
    NA, // lstore_0 = 63 (0x3f)
    NA, // lstore_1 = 64 (0x40)
    NA, // lstore_2 = 65 (0x41)
    NA, // lstore_3 = 66 (0x42)
    NA, // fstore_0 = 67 (0x43)
    NA, // fstore_1 = 68 (0x44)
    NA, // fstore_2 = 69 (0x45)
    NA, // fstore_3 = 70 (0x46)
    NA, // dstore_0 = 71 (0x47)
    NA, // dstore_1 = 72 (0x48)
    NA, // dstore_2 = 73 (0x49)
    NA, // dstore_3 = 74 (0x4a)
    NA, // astore_0 = 75 (0x4b)
    NA, // astore_1 = 76 (0x4c)
    NA, // astore_2 = 77 (0x4d)
    NA, // astore_3 = 78 (0x4e)
    -3, // iastore = 79 (0x4f)
    -4, // lastore = 80 (0x50)
    -3, // fastore = 81 (0x51)
    -4, // dastore = 82 (0x52)
    -3, // aastore = 83 (0x53)
    -3, // bastore = 84 (0x54)
    -3, // castore = 85 (0x55)
    -3, // sastore = 86 (0x56)
    -1, // pop = 87 (0x57)
    -2, // pop2 = 88 (0x58)
    1, // dup = 89 (0x59)
    1, // dup_x1 = 90 (0x5a)
    1, // dup_x2 = 91 (0x5b)
    2, // dup2 = 92 (0x5c)
    2, // dup2_x1 = 93 (0x5d)
    2, // dup2_x2 = 94 (0x5e)
    0, // swap = 95 (0x5f)
    -1, // iadd = 96 (0x60)
    -2, // ladd = 97 (0x61)
    -1, // fadd = 98 (0x62)
    -2, // dadd = 99 (0x63)
    -1, // isub = 100 (0x64)
    -2, // lsub = 101 (0x65)
    -1, // fsub = 102 (0x66)
    -2, // dsub = 103 (0x67)
    -1, // imul = 104 (0x68)
    -2, // lmul = 105 (0x69)
    -1, // fmul = 106 (0x6a)
    -2, // dmul = 107 (0x6b)
    -1, // idiv = 108 (0x6c)
    -2, // ldiv = 109 (0x6d)
    -1, // fdiv = 110 (0x6e)
    -2, // ddiv = 111 (0x6f)
    -1, // irem = 112 (0x70)
    -2, // lrem = 113 (0x71)
    -1, // frem = 114 (0x72)
    -2, // drem = 115 (0x73)
    0, // ineg = 116 (0x74)
    0, // lneg = 117 (0x75)
    0, // fneg = 118 (0x76)
    0, // dneg = 119 (0x77)
    -1, // ishl = 120 (0x78)
    -1, // lshl = 121 (0x79)
    -1, // ishr = 122 (0x7a)
    -1, // lshr = 123 (0x7b)
    -1, // iushr = 124 (0x7c)
    -1, // lushr = 125 (0x7d)
    -1, // iand = 126 (0x7e)
    -2, // land = 127 (0x7f)
    -1, // ior = 128 (0x80)
    -2, // lor = 129 (0x81)
    -1, // ixor = 130 (0x82)
    -2, // lxor = 131 (0x83)
    0, // iinc = 132 (0x84)
    1, // i2l = 133 (0x85)
    0, // i2f = 134 (0x86)
    1, // i2d = 135 (0x87)
    -1, // l2i = 136 (0x88)
    -1, // l2f = 137 (0x89)
    0, // l2d = 138 (0x8a)
    0, // f2i = 139 (0x8b)
    1, // f2l = 140 (0x8c)
    1, // f2d = 141 (0x8d)
    -1, // d2i = 142 (0x8e)
    0, // d2l = 143 (0x8f)
    -1, // d2f = 144 (0x90)
    0, // i2b = 145 (0x91)
    0, // i2c = 146 (0x92)
    0, // i2s = 147 (0x93)
    -3, // lcmp = 148 (0x94)
    -1, // fcmpl = 149 (0x95)
    -1, // fcmpg = 150 (0x96)
    -3, // dcmpl = 151 (0x97)
    -3, // dcmpg = 152 (0x98)
    -1, // ifeq = 153 (0x99)
    -1, // ifne = 154 (0x9a)
    -1, // iflt = 155 (0x9b)
    -1, // ifge = 156 (0x9c)
    -1, // ifgt = 157 (0x9d)
    -1, // ifle = 158 (0x9e)
    -2, // if_icmpeq = 159 (0x9f)
    -2, // if_icmpne = 160 (0xa0)
    -2, // if_icmplt = 161 (0xa1)
    -2, // if_icmpge = 162 (0xa2)
    -2, // if_icmpgt = 163 (0xa3)
    -2, // if_icmple = 164 (0xa4)
    -2, // if_acmpeq = 165 (0xa5)
    -2, // if_acmpne = 166 (0xa6)
    0, // goto = 167 (0xa7)
    1, // jsr = 168 (0xa8)
    0, // ret = 169 (0xa9)
    -1, // tableswitch = 170 (0xaa)
    -1, // lookupswitch = 171 (0xab)
    -1, // ireturn = 172 (0xac)
    -2, // lreturn = 173 (0xad)
    -1, // freturn = 174 (0xae)
    -2, // dreturn = 175 (0xaf)
    -1, // areturn = 176 (0xb0)
    0, // return = 177 (0xb1)
    NA, // getstatic = 178 (0xb2)
    NA, // putstatic = 179 (0xb3)
    NA, // getfield = 180 (0xb4)
    NA, // putfield = 181 (0xb5)
    NA, // invokevirtual = 182 (0xb6)
    NA, // invokespecial = 183 (0xb7)
    NA, // invokestatic = 184 (0xb8)
    NA, // invokeinterface = 185 (0xb9)
    NA, // invokedynamic = 186 (0xba)
    1, // new = 187 (0xbb)
    0, // newarray = 188 (0xbc)
    0, // anewarray = 189 (0xbd)
    0, // arraylength = 190 (0xbe)
    NA, // athrow = 191 (0xbf)
    0, // checkcast = 192 (0xc0)
    0, // instanceof = 193 (0xc1)
    -1, // monitorenter = 194 (0xc2)
    -1, // monitorexit = 195 (0xc3)
    NA, // wide = 196 (0xc4)
    NA, // multianewarray = 197 (0xc5)
    -1, // ifnull = 198 (0xc6)
    -1, // ifnonnull = 199 (0xc7)
    NA, // goto_w = 200 (0xc8)
    NA // jsr_w = 201 (0xc9)
  };

  /** Where the constants used in this MethodWriter must be stored. */
  private final SymbolTable symbolTable;

  // Note: fields are ordered as in the method_info structure, and those related to attributes are
  // ordered as in Section 4.7 of the JVMS.

  /**
   * The access_flags field of the method_info JVMS structure. This field can contain ASM specific
   * access flags, such as {@link Opcodes#ACC_DEPRECATED}, which are removed when generating the
   * ClassFile structure.
   */
  private final int accessFlags;

  /** The name_index field of the method_info JVMS structure. */
  private final int nameIndex;

  /** The name of this method. */
  private final String name;

  /** The descriptor_index field of the method_info JVMS structure. */
  private final int descriptorIndex;

  /** The descriptor of this method. */
  private final String descriptor;

  // Code attribute fields and sub attributes:

  /** The max_stack field of the Code attribute. */
  private int maxStack;

  /** The max_locals field of the Code attribute. */
  private int maxLocals;

  /** The 'code' field of the Code attribute. */
  private final ByteVector code = new ByteVector();

  /**
   * The first element in the exception handler list (used to generate the exception_table of the
   * Code attribute). The next ones can be accessed with the {@link Handler#nextHandler} field. May
   * be {@literal null}.
   */
  private Handler firstHandler;

  /**
   * The last element in the exception handler list (used to generate the exception_table of the
   * Code attribute). The next ones can be accessed with the {@link Handler#nextHandler} field. May
   * be {@literal null}.
   */
  private Handler lastHandler;

  /** The line_number_table_length field of the LineNumberTable code attribute. */
  private int lineNumberTableLength;

  /** The line_number_table array of the LineNumberTable code attribute, or {@literal null}. */
  private ByteVector lineNumberTable;

  /** The local_variable_table_length field of the LocalVariableTable code attribute. */
  private int localVariableTableLength;

  /**
   * The local_variable_table array of the LocalVariableTable code attribute, or {@literal null}.
   */
  private ByteVector localVariableTable;

  /** The local_variable_type_table_length field of the LocalVariableTypeTable code attribute. */
  private int localVariableTypeTableLength;

  /**
   * The local_variable_type_table array of the LocalVariableTypeTable code attribute, or {@literal
   * null}.
   */
  private ByteVector localVariableTypeTable;

  /** The number_of_entries field of the StackMapTable code attribute. */
  private int stackMapTableNumberOfEntries;

  /** The 'entries' array of the StackMapTable code attribute. */
  private ByteVector stackMapTableEntries;

  /**
   * The last runtime visible type annotation of the Code attribute. The previous ones can be
   * accessed with the {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastCodeRuntimeVisibleTypeAnnotation;

  /**
   * The last runtime invisible type annotation of the Code attribute. The previous ones can be
   * accessed with the {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastCodeRuntimeInvisibleTypeAnnotation;

  /**
   * The first non standard attribute of the Code attribute. The next ones can be accessed with the
   * {@link Attribute#nextAttribute} field. May be {@literal null}.
   *
   * <p><b>WARNING</b>: this list stores the attributes in the <i>reverse</i> order of their visit.
   * firstAttribute is actually the last attribute visited in {@link #visitAttribute}. The {@link
   * #putMethodInfo} method writes the attributes in the order defined by this list, i.e. in the
   * reverse order specified by the user.
   */
  private Attribute firstCodeAttribute;

  // Other method_info attributes:

  /** The number_of_exceptions field of the Exceptions attribute. */
  private final int numberOfExceptions;

  /** The exception_index_table array of the Exceptions attribute, or {@literal null}. */
  private final int[] exceptionIndexTable;

  /** The signature_index field of the Signature attribute. */
  private final int signatureIndex;

  /**
   * The last runtime visible annotation of this method. The previous ones can be accessed with the
   * {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastRuntimeVisibleAnnotation;

  /**
   * The last runtime invisible annotation of this method. The previous ones can be accessed with
   * the {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastRuntimeInvisibleAnnotation;

  /** The number of method parameters that can have runtime visible annotations, or 0. */
  private int visibleAnnotableParameterCount;

  /**
   * The runtime visible parameter annotations of this method. Each array element contains the last
   * annotation of a parameter (which can be {@literal null} - the previous ones can be accessed
   * with the {@link AnnotationWriter#previousAnnotation} field). May be {@literal null}.
   */
  private AnnotationWriter[] lastRuntimeVisibleParameterAnnotations;

  /** The number of method parameters that can have runtime visible annotations, or 0. */
  private int invisibleAnnotableParameterCount;

  /**
   * The runtime invisible parameter annotations of this method. Each array element contains the
   * last annotation of a parameter (which can be {@literal null} - the previous ones can be
   * accessed with the {@link AnnotationWriter#previousAnnotation} field). May be {@literal null}.
   */
  private AnnotationWriter[] lastRuntimeInvisibleParameterAnnotations;

  /**
   * The last runtime visible type annotation of this method. The previous ones can be accessed with
   * the {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastRuntimeVisibleTypeAnnotation;

  /**
   * The last runtime invisible type annotation of this method. The previous ones can be accessed
   * with the {@link AnnotationWriter#previousAnnotation} field. May be {@literal null}.
   */
  private AnnotationWriter lastRuntimeInvisibleTypeAnnotation;

  /** The default_value field of the AnnotationDefault attribute, or {@literal null}. */
  private ByteVector defaultValue;

  /** The parameters_count field of the MethodParameters attribute. */
  private int parametersCount;

  /** The 'parameters' array of the MethodParameters attribute, or {@literal null}. */
  private ByteVector parameters;

  /**
   * The first non standard attribute of this method. The next ones can be accessed with the {@link
   * Attribute#nextAttribute} field. May be {@literal null}.
   *
   * <p><b>WARNING</b>: this list stores the attributes in the <i>reverse</i> order of their visit.
   * firstAttribute is actually the last attribute visited in {@link #visitAttribute}. The {@link
   * #putMethodInfo} method writes the attributes in the order defined by this list, i.e. in the
   * reverse order specified by the user.
   */
  private Attribute firstAttribute;

  // -----------------------------------------------------------------------------------------------
  // Fields used to compute the maximum stack size and number of locals, and the stack map frames
  // -----------------------------------------------------------------------------------------------

  /**
   * Indicates what must be computed. Must be one of {@link #COMPUTE_ALL_FRAMES}, {@link
   * #COMPUTE_INSERTED_FRAMES}, {@link #COMPUTE_MAX_STACK_AND_LOCAL} or {@link #COMPUTE_NOTHING}.
   */
  private final int compute;

  /**
   * The first basic block of the method. The next ones (in bytecode offset order) can be accessed
   * with the {@link Label#nextBasicBlock} field.
   */
  private Label firstBasicBlock;

  /**
   * The last basic block of the method (in bytecode offset order). This field is updated each time
   * a basic block is encountered, and is used to append it at the end of the basic block list.
   */
  private Label lastBasicBlock;

  /**
   * The current basic block, i.e. the basic block of the last visited instruction. When {@link
   * #compute} is equal to {@link #COMPUTE_MAX_STACK_AND_LOCAL} or {@link #COMPUTE_ALL_FRAMES}, this
   * field is {@literal null} for unreachable code. When {@link #compute} is equal to {@link
   * #COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES} or {@link #COMPUTE_INSERTED_FRAMES}, this field stays
   * unchanged throughout the whole method (i.e. the whole code is seen as a single basic block;
   * indeed, the existing frames are sufficient by hypothesis to compute any intermediate frame -
   * and the maximum stack size as well - without using any control flow graph).
   */
  private Label currentBasicBlock;

  /**
   * The relative stack size after the last visited instruction. This size is relative to the
   * beginning of {@link #currentBasicBlock}, i.e. the true stack size after the last visited
   * instruction is equal to the {@link Label#inputStackSize} of the current basic block plus {@link
   * #relativeStackSize}. When {@link #compute} is equal to {@link
   * #COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES}, {@link #currentBasicBlock} is always the start of
   * the method, so this relative size is also equal to the absolute stack size after the last
   * visited instruction.
   */
  // 相对于currentBasicBlock的inputStackSize的相对值，真实的stackSize等于inputStackSize + relativeStackSize
  private int relativeStackSize;

  /**
   * The maximum relative stack size after the last visited instruction. This size is relative to
   * the beginning of {@link #currentBasicBlock}, i.e. the true maximum stack size after the last
   * visited instruction is equal to the {@link Label#inputStackSize} of the current basic block
   * plus {@link #maxRelativeStackSize}.When {@link #compute} is equal to {@link
   * #COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES}, {@link #currentBasicBlock} is always the start of
   * the method, so this relative size is also equal to the absolute maximum stack size after the
   * last visited instruction.
   */
  // 在最后一个visit指令结束之后的相对最大size，这个size是currentBasicBlock的inputStackSize的相对值。
  // 也就是说，真实的最大stack size等于currentBasicBlock的inputStackSize + maxRelativeStackSize。
  // 当compute等于COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES的时候，currentBasicBlock总是等于方法的开始，所以这个相对的最大stack size
  // 也就是绝对的stack size了
  private int maxRelativeStackSize;

  /** The number of local variables in the last visited stack map frame. */
  private int currentLocals;

  /** The bytecode offset of the last frame that was written in {@link #stackMapTableEntries}. */
  private int previousFrameOffset;

  /**
   * The last frame that was written in {@link #stackMapTableEntries}. This field has the same
   * format as {@link #currentFrame}.
   */
  private int[] previousFrame;

  /**
   * The current stack map frame. The first element contains the bytecode offset of the instruction
   * to which the frame corresponds, the second element is the number of locals and the third one is
   * the number of stack elements. The local variables start at index 3 and are followed by the
   * operand stack elements. In summary frame[0] = offset, frame[1] = numLocal, frame[2] = numStack.
   * Local variables and operand stack entries contain abstract types, as defined in {@link Frame},
   * but restricted to {@link Frame#CONSTANT_KIND}, {@link Frame#REFERENCE_KIND} or {@link
   * Frame#UNINITIALIZED_KIND} abstract types. Long and double types use only one array entry.
   */
  // 第一个元素是frame的字节码偏移量，第二个元素是locals的数量，第三个元素是stackSize，然后之后一次是local variables和 operand stack entry
  private int[] currentFrame;

  /** Whether this method contains subroutines. */
  private boolean hasSubroutines;

  // -----------------------------------------------------------------------------------------------
  // Other miscellaneous status fields
  // -----------------------------------------------------------------------------------------------

  /** Whether the bytecode of this method contains ASM specific instructions. */
  private boolean hasAsmInstructions;

  /**
   * The start offset of the last visited instruction. Used to set the offset field of type
   * annotations of type 'offset_target' (see <a
   * href="https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.7.20.1">JVMS
   * 4.7.20.1</a>).
   */
  private int lastBytecodeOffset;

  /**
   * The offset in bytes in {@link SymbolTable#getSource} from which the method_info for this method
   * (excluding its first 6 bytes) must be copied, or 0.
   */
  private int sourceOffset;

  /**
   * The length in bytes in {@link SymbolTable#getSource} which must be copied to get the
   * method_info for this method (excluding its first 6 bytes for access_flags, name_index and
   * descriptor_index).
   */
  private int sourceLength;

  // -----------------------------------------------------------------------------------------------
  // Constructor and accessors
  // -----------------------------------------------------------------------------------------------

  /**
   * Constructs a new {@link MethodWriter}.
   *
   * @param symbolTable where the constants used in this AnnotationWriter must be stored.
   * @param access the method's access flags (see {@link Opcodes}).
   * @param name the method's name.
   * @param descriptor the method's descriptor (see {@link Type}).
   * @param signature the method's signature. May be {@literal null}.
   * @param exceptions the internal names of the method's exceptions. May be {@literal null}.
   * @param compute indicates what must be computed (see #compute).
   */
  MethodWriter(
      final SymbolTable symbolTable,
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final String[] exceptions,
      final int compute) {
    super(/* latest api = */ Opcodes.ASM7);
	// 将传入的symbolTable赋值给自身属性
    this.symbolTable = symbolTable;
	// 如果方法名是<init>，那么将传入的access和构造器的访问修饰符做或操作，否则，将传入的access赋值给自身的accessFlags属性
    this.accessFlags = "<init>".equals(name) ? access | Constants.ACC_CONSTRUCTOR : access;
	// 向常量池中添加值为 方法名 的CONSTANT_UTF8_info类型的常量，并返回常量的序号
    this.nameIndex = symbolTable.addConstantUtf8(name);
	// 将方法名赋值给自身的name属性持有
    this.name = name;
	// 向常量池中添加值为descriptor的CONSTANT_UTF8_info常量
    this.descriptorIndex = symbolTable.addConstantUtf8(descriptor);
	// 并且将方法描述符赋值给descriptor属性持有
    this.descriptor = descriptor;
	// 如果signature不为null，向常量池中添加对应的常量，否则，将signatureIndex赋值为0
    this.signatureIndex = signature == null ? 0 : symbolTable.addConstantUtf8(signature);
	// 如果方法上声明的要抛出的异常存在
    if (exceptions != null && exceptions.length > 0) {
      numberOfExceptions = exceptions.length;
      this.exceptionIndexTable = new int[numberOfExceptions];
	  // 遍历异常，向常量池中添加CONSTANT_CLASS_info类型的常量，并且将常量序号保存在exceptionIndexTable中
      for (int i = 0; i < numberOfExceptions; ++i) {
        this.exceptionIndexTable[i] = symbolTable.addConstantClass(exceptions[i]).index;
      }
    }
	// 如果不存在声明的异常，将numberOfException赋值为0
	else {
      numberOfExceptions = 0;
      this.exceptionIndexTable = null;
    }
	// 赋值compute属性
    this.compute = compute;
	// 如果compute不等于COMPUTE_NOTHING
    if (compute != COMPUTE_NOTHING) {
      // Update maxLocals and currentLocals.
		// 根据方法描述符计算方法的参数和返回值个数，其中后两位表示返回值所占的slot个数，前面的位置表示参数所占的slot个数。
		// 因此将结果向右移2位，获取到的是参数所占的slot个数
      int argumentsSize = Type.getArgumentsAndReturnSizes(descriptor) >> 2;
	  // 如果方法是static的，那么需要将this参数去掉，所以将参数slot-1
      if ((access & Opcodes.ACC_STATIC) != 0) {
        --argumentsSize;
      }
	  // 将maxLocals设置为参数所需要的slot个数
      maxLocals = argumentsSize;
	  // 将currentLocals也设置参数所需的slot个数
      currentLocals = argumentsSize;
      // Create and visit the label for the first basic block.
		// 创建一个Label赋值给firstBasicBlock
      firstBasicBlock = new Label();
	  // 然后调用visitLabel方法
      visitLabel(firstBasicBlock);
    }
  }

  boolean hasFrames() {
    return stackMapTableNumberOfEntries > 0;
  }

  boolean hasAsmInstructions() {
    return hasAsmInstructions;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementation of the MethodVisitor abstract class
  // -----------------------------------------------------------------------------------------------

  @Override
  public void visitParameter(final String name, final int access) {
    if (parameters == null) {
      parameters = new ByteVector();
    }
    ++parametersCount;
    parameters.putShort((name == null) ? 0 : symbolTable.addConstantUtf8(name)).putShort(access);
  }

  @Override
  public AnnotationVisitor visitAnnotationDefault() {
    defaultValue = new ByteVector();
    return new AnnotationWriter(symbolTable, /* useNamedValues = */ false, defaultValue, null);
  }

  @Override
  public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
    if (visible) {
      return lastRuntimeVisibleAnnotation =
          AnnotationWriter.create(symbolTable, descriptor, lastRuntimeVisibleAnnotation);
    } else {
      return lastRuntimeInvisibleAnnotation =
          AnnotationWriter.create(symbolTable, descriptor, lastRuntimeInvisibleAnnotation);
    }
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
    if (visible) {
      return lastRuntimeVisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable, typeRef, typePath, descriptor, lastRuntimeVisibleTypeAnnotation);
    } else {
      return lastRuntimeInvisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable, typeRef, typePath, descriptor, lastRuntimeInvisibleTypeAnnotation);
    }
  }

  @Override
  public void visitAnnotableParameterCount(final int parameterCount, final boolean visible) {
    if (visible) {
      visibleAnnotableParameterCount = parameterCount;
    } else {
      invisibleAnnotableParameterCount = parameterCount;
    }
  }

  @Override
  public AnnotationVisitor visitParameterAnnotation(
      final int parameter, final String annotationDescriptor, final boolean visible) {
    if (visible) {
      if (lastRuntimeVisibleParameterAnnotations == null) {
        lastRuntimeVisibleParameterAnnotations =
            new AnnotationWriter[Type.getArgumentTypes(descriptor).length];
      }
      return lastRuntimeVisibleParameterAnnotations[parameter] =
          AnnotationWriter.create(
              symbolTable, annotationDescriptor, lastRuntimeVisibleParameterAnnotations[parameter]);
    } else {
      if (lastRuntimeInvisibleParameterAnnotations == null) {
        lastRuntimeInvisibleParameterAnnotations =
            new AnnotationWriter[Type.getArgumentTypes(descriptor).length];
      }
      return lastRuntimeInvisibleParameterAnnotations[parameter] =
          AnnotationWriter.create(
              symbolTable,
              annotationDescriptor,
              lastRuntimeInvisibleParameterAnnotations[parameter]);
    }
  }

  @Override
  public void visitAttribute(final Attribute attribute) {
    // Store the attributes in the <i>reverse</i> order of their visit by this method.
    if (attribute.isCodeAttribute()) {
      attribute.nextAttribute = firstCodeAttribute;
      firstCodeAttribute = attribute;
    } else {
      attribute.nextAttribute = firstAttribute;
      firstAttribute = attribute;
    }
  }

  @Override
  public void visitCode() {
    // Nothing to do.
  }

  @Override
  public void visitFrame(
      final int type,
      final int numLocal,
      final Object[] local,
      final int numStack,
      final Object[] stack) {
    if (compute == COMPUTE_ALL_FRAMES) {
      return;
    }

    if (compute == COMPUTE_INSERTED_FRAMES) {
      if (currentBasicBlock.frame == null) {
        // This should happen only once, for the implicit first frame (which is explicitly visited
        // in ClassReader if the EXPAND_ASM_INSNS option is used - and COMPUTE_INSERTED_FRAMES
        // can't be set if EXPAND_ASM_INSNS is not used).
        currentBasicBlock.frame = new CurrentFrame(currentBasicBlock);
        currentBasicBlock.frame.setInputFrameFromDescriptor(
            symbolTable, accessFlags, descriptor, numLocal);
        currentBasicBlock.frame.accept(this);
      } else {
        if (type == Opcodes.F_NEW) {
          currentBasicBlock.frame.setInputFrameFromApiFormat(
              symbolTable, numLocal, local, numStack, stack);
        }
        // If type is not F_NEW then it is F_INSERT by hypothesis, and currentBlock.frame contains
        // the stack map frame at the current instruction, computed from the last F_NEW frame and
        // the bytecode instructions in between (via calls to CurrentFrame#execute).
        currentBasicBlock.frame.accept(this);
      }
    } else if (type == Opcodes.F_NEW) {
      if (previousFrame == null) {
        int argumentsSize = Type.getArgumentsAndReturnSizes(descriptor) >> 2;
        Frame implicitFirstFrame = new Frame(new Label());
        implicitFirstFrame.setInputFrameFromDescriptor(
            symbolTable, accessFlags, descriptor, argumentsSize);
        implicitFirstFrame.accept(this);
      }
      currentLocals = numLocal;
      int frameIndex = visitFrameStart(code.length, numLocal, numStack);
      for (int i = 0; i < numLocal; ++i) {
        currentFrame[frameIndex++] = Frame.getAbstractTypeFromApiFormat(symbolTable, local[i]);
      }
      for (int i = 0; i < numStack; ++i) {
        currentFrame[frameIndex++] = Frame.getAbstractTypeFromApiFormat(symbolTable, stack[i]);
      }
      visitFrameEnd();
    } else {
      if (symbolTable.getMajorVersion() < Opcodes.V1_6) {
        throw new IllegalArgumentException("Class versions V1_5 or less must use F_NEW frames.");
      }
      int offsetDelta;
      if (stackMapTableEntries == null) {
        stackMapTableEntries = new ByteVector();
        offsetDelta = code.length;
      } else {
        offsetDelta = code.length - previousFrameOffset - 1;
        if (offsetDelta < 0) {
          if (type == Opcodes.F_SAME) {
            return;
          } else {
            throw new IllegalStateException();
          }
        }
      }

      switch (type) {
        case Opcodes.F_FULL:
          currentLocals = numLocal;
          stackMapTableEntries.putByte(Frame.FULL_FRAME).putShort(offsetDelta).putShort(numLocal);
          for (int i = 0; i < numLocal; ++i) {
            putFrameType(local[i]);
          }
          stackMapTableEntries.putShort(numStack);
          for (int i = 0; i < numStack; ++i) {
            putFrameType(stack[i]);
          }
          break;
        case Opcodes.F_APPEND:
          currentLocals += numLocal;
          stackMapTableEntries.putByte(Frame.SAME_FRAME_EXTENDED + numLocal).putShort(offsetDelta);
          for (int i = 0; i < numLocal; ++i) {
            putFrameType(local[i]);
          }
          break;
        case Opcodes.F_CHOP:
          currentLocals -= numLocal;
          stackMapTableEntries.putByte(Frame.SAME_FRAME_EXTENDED - numLocal).putShort(offsetDelta);
          break;
        case Opcodes.F_SAME:
          if (offsetDelta < 64) {
            stackMapTableEntries.putByte(offsetDelta);
          } else {
            stackMapTableEntries.putByte(Frame.SAME_FRAME_EXTENDED).putShort(offsetDelta);
          }
          break;
        case Opcodes.F_SAME1:
          if (offsetDelta < 64) {
            stackMapTableEntries.putByte(Frame.SAME_LOCALS_1_STACK_ITEM_FRAME + offsetDelta);
          } else {
            stackMapTableEntries
                .putByte(Frame.SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED)
                .putShort(offsetDelta);
          }
          putFrameType(stack[0]);
          break;
        default:
          throw new IllegalArgumentException();
      }

      previousFrameOffset = code.length;
      ++stackMapTableNumberOfEntries;
    }

    if (compute == COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES) {
      relativeStackSize = numStack;
      for (int i = 0; i < numStack; ++i) {
        if (stack[i] == Opcodes.LONG || stack[i] == Opcodes.DOUBLE) {
          relativeStackSize++;
        }
      }
      if (relativeStackSize > maxRelativeStackSize) {
        maxRelativeStackSize = relativeStackSize;
      }
    }

    maxStack = Math.max(maxStack, numStack);
    maxLocals = Math.max(maxLocals, currentLocals);
  }

  @Override
  public void visitInsn(final int opcode) {
	  // 将lastBytecodeOffset设置为code.length
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 然后直接向code中添加对应的1个字节的字节码
    code.putByte(opcode);
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(opcode, 0, null, null);
      } else {
        int size = relativeStackSize + STACK_SIZE_DELTA[opcode];
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
      if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
        endCurrentBasicBlockWithNoSuccessor();
      }
    }
  }

  @Override
  public void visitIntInsn(final int opcode, final int operand) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    if (opcode == Opcodes.SIPUSH) {
      code.put12(opcode, operand);
    } else { // BIPUSH or NEWARRAY
      code.put11(opcode, operand);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(opcode, operand, null, null);
      } else if (opcode != Opcodes.NEWARRAY) {
        // The stack size delta is 1 for BIPUSH or SIPUSH, and 0 for NEWARRAY.
        int size = relativeStackSize + 1;
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
    }
  }

  @Override
  // 因为是visitVarInsn方法，因此opcode已经被限制在了iload lload fload dload aload istore lstore fstore dstore astore ret这几个字节码中了。
  // 可以去Opcodes查看对应的字节码的注释，就能区别出它属于哪个方法
  public void visitVarInsn(final int opcode, final int var) {
	  // lastBytecodeOffset设置为code的length
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 如果var小于4 并且 opcode不等于ret
    if (var < 4 && opcode != Opcodes.RET) {
		// 获取优化的字节码
      int optimizedOpcode;
	  // 如果opcode小于istore，说明是load相关的字节码，由于方法的限制，那么只可能是iload lload fload dload aload，且var < 4
      if (opcode < Opcodes.ISTORE) {
		  // 将opcode和iload的差值计算出来，又因为load相关的字节码都会存在0 1 2 3这四个值，因此将它们的差距乘4(向左移两位)，
		  // 就能得到其对应的类型的load_0操作，然后再加上var，就是对应的load_var操作，因为这里的var是小于4的，所以一定能找到对应的字节码。
		  // 这样就将opcode和var合成了一个字节码了
        optimizedOpcode = Constants.ILOAD_0 + ((opcode - Opcodes.ILOAD) << 2) + var;
      }
	  // 同理如果opcode大于等于istore，说明是store相关的字节码，由于方法的限制，那么只可能是istore lstore fstore dstore astore，且var < 4
	  else {
		  // 和load进行相同的操作，就能得到合成的一个store_var字节码
        optimizedOpcode = Constants.ISTORE_0 + ((opcode - Opcodes.ISTORE) << 2) + var;
      }
	  // 将优化后的字节码直接放入code中
      code.putByte(optimizedOpcode);
    }
	// 如果var大于等于了256，那么一个字节已经包含不下了，需要两个字节，那么需要在字节码前面使用wide字节码，表示下一个字节码需要操作两个字节的操作数
	else if (var >= 256) {
		// 向code中添加wide 然后添加1个字节的opcode 添加2个字节的var
      code.putByte(Constants.WIDE).put12(opcode, var);
    }
	// 其他情况下，没办法进行字节码和操作数合并的优化操作，那么向code中添加1个字节opcode和1个字节的var
	else {
      code.put11(opcode, var);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
	  // 如果需要的话，更新最大的stack size 和 number of locals，以及stack map frames
	  // 如果currentBasicBlock不为null
    if (currentBasicBlock != null) {
		// 并且compute等于COMPUTE_ALL_FRAMES 或者 COMPUTE_INSERTED_FRAMES
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
		  // 调用currentBasicBlock持有的frame的execute方法
        currentBasicBlock.frame.execute(opcode, var, null, null);
      } else {
		  // 如果指令为RET
        if (opcode == Opcodes.RET) {
          // No stack size delta.
          currentBasicBlock.flags |= Label.FLAG_SUBROUTINE_END;
		  // 将relativeStackSize设置到currentBasicBlock的outputStackSize中
          currentBasicBlock.outputStackSize = (short) relativeStackSize;
		  // 然后结束当前block，并且没有successor
          endCurrentBasicBlockWithNoSuccessor();
        } else { // xLOAD or xSTORE
			// 如果是load或者store指令
			// 根据指令所带来的stack delta，计算relativeStackSize，并且根据情况更新maxRelativeStackSize
          int size = relativeStackSize + STACK_SIZE_DELTA[opcode];
          if (size > maxRelativeStackSize) {
            maxRelativeStackSize = size;
          }
          relativeStackSize = size;
        }
      }
    }
	// 如果compute不等于COMPUTE_NOTHING
    if (compute != COMPUTE_NOTHING) {
      int currentMaxLocals;
	  // 当指令时long或者double相关的，将var + 2；否则将var + 1，作为currentMaxLocals的值
      if (opcode == Opcodes.LLOAD
          || opcode == Opcodes.DLOAD
          || opcode == Opcodes.LSTORE
          || opcode == Opcodes.DSTORE) {
        currentMaxLocals = var + 2;
      } else {
        currentMaxLocals = var + 1;
      }
	  // 当currentMaxLocals大于了maxLocals，赋值maxLocals属性为currentMaxLocals
      if (currentMaxLocals > maxLocals) {
        maxLocals = currentMaxLocals;
      }
    }
    if (opcode >= Opcodes.ISTORE && compute == COMPUTE_ALL_FRAMES && firstHandler != null) {
      // If there are exception handler blocks, each instruction within a handler range is, in
      // theory, a basic block (since execution can jump from this instruction to the exception
      // handler). As a consequence, the local variable types at the beginning of the handler
      // block should be the merge of the local variable types at all the instructions within the
      // handler range. However, instead of creating a basic block for each instruction, we can
      // get the same result in a more efficient way. Namely, by starting a new basic block after
      // each xSTORE instruction, which is what we do here.
      visitLabel(new Label());
    }
  }

  @Override
  public void visitTypeInsn(final int opcode, final String type) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 向常量池中添加CONSTANT_CLASS_info类型的常量
    Symbol typeSymbol = symbolTable.addConstantClass(type);
	// 然后向code中添加一个字节的opcode 和2个字节的CONSTANT_CLASS_info常量索引
    code.put12(opcode, typeSymbol.index);
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(opcode, lastBytecodeOffset, typeSymbol, symbolTable);
      } else if (opcode == Opcodes.NEW) {
        // The stack size delta is 1 for NEW, and 0 for ANEWARRAY, CHECKCAST, or INSTANCEOF.
        int size = relativeStackSize + 1;
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
    }
  }

  @Override
  public void visitFieldInsn(
      final int opcode, final String owner, final String name, final String descriptor) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 向常量池中添加CONSTANT_FIELDREF_info类型的常量，其中会包括对CONSTANT_CLASS_info和CONSTANT_NameAndType_info常量的添加
    Symbol fieldrefSymbol = symbolTable.addConstantFieldref(owner, name, descriptor);
	// 然后向code中添加对应的字段操作的字节码，以及对应字段在常量池中的索引
    code.put12(opcode, fieldrefSymbol.index);
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(opcode, 0, fieldrefSymbol, symbolTable);
      } else {
        int size;
        char firstDescChar = descriptor.charAt(0);
        switch (opcode) {
          case Opcodes.GETSTATIC:
            size = relativeStackSize + (firstDescChar == 'D' || firstDescChar == 'J' ? 2 : 1);
            break;
          case Opcodes.PUTSTATIC:
            size = relativeStackSize + (firstDescChar == 'D' || firstDescChar == 'J' ? -2 : -1);
            break;
          case Opcodes.GETFIELD:
            size = relativeStackSize + (firstDescChar == 'D' || firstDescChar == 'J' ? 1 : 0);
            break;
          case Opcodes.PUTFIELD:
          default:
            size = relativeStackSize + (firstDescChar == 'D' || firstDescChar == 'J' ? -3 : -2);
            break;
        }
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
    }
  }

  @Override
  public void visitMethodInsn(
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
	  // 将lastBytecodeOffset设置为code.length
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 向常量池中添加CONSTANT_METHODREF_info类型的常量，其中会尝试添加CONSTANT_CLASS_info和CONSTANT_NameAndType_info类型的常量
    Symbol methodrefSymbol = symbolTable.addConstantMethodref(owner, name, descriptor, isInterface);
	// 如果字节码是invokeinterface
	  if (opcode == Opcodes.INVOKEINTERFACE) {
		  // 1.向code中添加1个字节的invokeinterface字节码 以及 2个字节的CONSTANT_INTERFACE_METHODREF_info常量的索引；
		  // 2.然后根据method的描述符返回参数和返回值的个数，然后向右移动两位，返回参数的个数。
		  // 向code中添加1个字节的参数个数，以及一个字节的0
		  code.put12(Opcodes.INVOKEINTERFACE, methodrefSymbol.index)
				  .put11(methodrefSymbol.getArgumentsAndReturnSizes() >> 2, 0);
	  }
	  // 如果是字节码是其他invoke，那么就添加1个字节的字节码 以及 2个字节的常量索引
	  else {
		  code.put12(opcode, methodrefSymbol.index);
	  }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(opcode, 0, methodrefSymbol, symbolTable);
      } else {
		  // 根据描述符获取参数和返回值个数
        int argumentsAndReturnSize = methodrefSymbol.getArgumentsAndReturnSizes();
		// 使用返回值的个数减去参数的个数，得到stackSize需要变动的量
        int stackSizeDelta = (argumentsAndReturnSize & 3) - (argumentsAndReturnSize >> 2);
        int size;
		// 如果是invokestatic，参数会少一个this，那么需要将delta + 1；
		  // 其余请不需要将delta + 1，计算出相对的stackSize
        if (opcode == Opcodes.INVOKESTATIC) {
          size = relativeStackSize + stackSizeDelta + 1;
        } else {
          size = relativeStackSize + stackSizeDelta;
        }
		// 如果size大于了最大的相对stackSize，更新maxRelativeStackSize的值
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
		// 将size赋值给relativeStackSize
        relativeStackSize = size;
      }
    }
  }

  @Override
  public void visitInvokeDynamicInsn(
      final String name,
      final String descriptor,
      final Handle bootstrapMethodHandle,
      final Object... bootstrapMethodArguments) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    Symbol invokeDynamicSymbol =
        symbolTable.addConstantInvokeDynamic(
            name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    code.put12(Opcodes.INVOKEDYNAMIC, invokeDynamicSymbol.index);
    code.putShort(0);
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(Opcodes.INVOKEDYNAMIC, 0, invokeDynamicSymbol, symbolTable);
      } else {
        int argumentsAndReturnSize = invokeDynamicSymbol.getArgumentsAndReturnSizes();
        int stackSizeDelta = (argumentsAndReturnSize & 3) - (argumentsAndReturnSize >> 2) + 1;
        int size = relativeStackSize + stackSizeDelta;
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
    }
  }

  @Override
  public void visitJumpInsn(final int opcode, final Label label) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    // Compute the 'base' opcode, i.e. GOTO or JSR if opcode is GOTO_W or JSR_W, otherwise opcode.
    int baseOpcode =
        opcode >= Constants.GOTO_W ? opcode - Constants.WIDE_JUMP_OPCODE_DELTA : opcode;
    boolean nextInsnIsJumpTarget = false;
    if ((label.flags & Label.FLAG_RESOLVED) != 0
        && label.bytecodeOffset - code.length < Short.MIN_VALUE) {
      // Case of a backward jump with an offset < -32768. In this case we automatically replace GOTO
      // with GOTO_W, JSR with JSR_W and IFxxx <l> with IFNOTxxx <L> GOTO_W <l> L:..., where
      // IFNOTxxx is the "opposite" opcode of IFxxx (e.g. IFNE for IFEQ) and where <L> designates
      // the instruction just after the GOTO_W.
      if (baseOpcode == Opcodes.GOTO) {
        code.putByte(Constants.GOTO_W);
      } else if (baseOpcode == Opcodes.JSR) {
        code.putByte(Constants.JSR_W);
      } else {
        // Put the "opposite" opcode of baseOpcode. This can be done by flipping the least
        // significant bit for IFNULL and IFNONNULL, and similarly for IFEQ ... IF_ACMPEQ (with a
        // pre and post offset by 1). The jump offset is 8 bytes (3 for IFNOTxxx, 5 for GOTO_W).
        code.putByte(baseOpcode >= Opcodes.IFNULL ? baseOpcode ^ 1 : ((baseOpcode + 1) ^ 1) - 1);
        code.putShort(8);
        // Here we could put a GOTO_W in theory, but if ASM specific instructions are used in this
        // method or another one, and if the class has frames, we will need to insert a frame after
        // this GOTO_W during the additional ClassReader -> ClassWriter round trip to remove the ASM
        // specific instructions. To not miss this additional frame, we need to use an ASM_GOTO_W
        // here, which has the unfortunate effect of forcing this additional round trip (which in
        // some case would not have been really necessary, but we can't know this at this point).
        code.putByte(Constants.ASM_GOTO_W);
        hasAsmInstructions = true;
        // The instruction after the GOTO_W becomes the target of the IFNOT instruction.
        nextInsnIsJumpTarget = true;
      }
      label.put(code, code.length - 1, true);
    } else if (baseOpcode != opcode) {
      // Case of a GOTO_W or JSR_W specified by the user (normally ClassReader when used to remove
      // ASM specific instructions). In this case we keep the original instruction.
      code.putByte(opcode);
      label.put(code, code.length - 1, true);
    } else {
      // Case of a jump with an offset >= -32768, or of a jump with an unknown offset. In these
      // cases we store the offset in 2 bytes (which will be increased via a ClassReader ->
      // ClassWriter round trip if it turns out that 2 bytes are not sufficient).
      code.putByte(baseOpcode);
      label.put(code, code.length - 1, false);
    }

    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      Label nextBasicBlock = null;
      if (compute == COMPUTE_ALL_FRAMES) {
        currentBasicBlock.frame.execute(baseOpcode, 0, null, null);
        // Record the fact that 'label' is the target of a jump instruction.
        label.getCanonicalInstance().flags |= Label.FLAG_JUMP_TARGET;
        // Add 'label' as a successor of the current basic block.
        addSuccessorToCurrentBasicBlock(Edge.JUMP, label);
        if (baseOpcode != Opcodes.GOTO) {
          // The next instruction starts a new basic block (except for GOTO: by default the code
          // following a goto is unreachable - unless there is an explicit label for it - and we
          // should not compute stack frame types for its instructions).
          nextBasicBlock = new Label();
        }
      } else if (compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(baseOpcode, 0, null, null);
      } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES) {
        // No need to update maxRelativeStackSize (the stack size delta is always negative).
        relativeStackSize += STACK_SIZE_DELTA[baseOpcode];
      } else {
        if (baseOpcode == Opcodes.JSR) {
          // Record the fact that 'label' designates a subroutine, if not already done.
          if ((label.flags & Label.FLAG_SUBROUTINE_START) == 0) {
            label.flags |= Label.FLAG_SUBROUTINE_START;
            hasSubroutines = true;
          }
          currentBasicBlock.flags |= Label.FLAG_SUBROUTINE_CALLER;
          // Note that, by construction in this method, a block which calls a subroutine has at
          // least two successors in the control flow graph: the first one (added below) leads to
          // the instruction after the JSR, while the second one (added here) leads to the JSR
          // target. Note that the first successor is virtual (it does not correspond to a possible
          // execution path): it is only used to compute the successors of the basic blocks ending
          // with a ret, in {@link Label#addSubroutineRetSuccessors}.
          addSuccessorToCurrentBasicBlock(relativeStackSize + 1, label);
          // The instruction after the JSR starts a new basic block.
          nextBasicBlock = new Label();
        } else {
          // No need to update maxRelativeStackSize (the stack size delta is always negative).
          relativeStackSize += STACK_SIZE_DELTA[baseOpcode];
          addSuccessorToCurrentBasicBlock(relativeStackSize, label);
        }
      }
      // If the next instruction starts a new basic block, call visitLabel to add the label of this
      // instruction as a successor of the current block, and to start a new basic block.
      if (nextBasicBlock != null) {
        if (nextInsnIsJumpTarget) {
          nextBasicBlock.flags |= Label.FLAG_JUMP_TARGET;
        }
        visitLabel(nextBasicBlock);
      }
      if (baseOpcode == Opcodes.GOTO) {
        endCurrentBasicBlockWithNoSuccessor();
      }
    }
  }

  @Override
  public void visitLabel(final Label label) {
    // Resolve the forward references to this label, if any.
	  // 设置label对应的offset，即在code中的字节码偏移量
	  // 解析那些跳转到该label的forwardReferences，并且返回是否存在asm的指令
    hasAsmInstructions |= label.resolve(code.data, code.length);
    // visitLabel starts a new basic block (except for debug only labels), so we need to update the
    // previous and current block references and list of successors.
	  // 如果label的flag是FLAG_DEBUG_ONLY，直接返回
    if ((label.flags & Label.FLAG_DEBUG_ONLY) != 0) {
      return;
    }
	// 如果compute等于COMPUTE_ALL_FRAMES
    if (compute == COMPUTE_ALL_FRAMES) {
		// 如果currentBasicBlock不为null
      if (currentBasicBlock != null) {
		  // 如果label的字节码偏移量等于 当前basicBlock的字节码偏移量
        if (label.bytecodeOffset == currentBasicBlock.bytecodeOffset) {
          // We use {@link Label#getCanonicalInstance} to store the state of a basic block in only
          // one place, but this does not work for labels which have not been visited yet.
          // Therefore, when we detect here two labels having the same bytecode offset, we need to
          // - consolidate the state scattered in these two instances into the canonical instance:
			// 我们使用getCanonicalInstance来存储basicBlock的state在一个地方，但是我们这对还没有访问过的label不起作用，
			// 因此，但我们发现两个label存在相同的偏移量时，我们需要合并他们的state到规范实例中
			// 将当前label的flags 或上 FLAG_JUMP_TARGET
          currentBasicBlock.flags |= (label.flags & Label.FLAG_JUMP_TARGET);
          // - make sure the two instances share the same Frame instance (the implementation of
          // {@link Label#getCanonicalInstance} relies on this property; here label.frame should be
          // null):
			// 确保两个实例共享相同的frame实例
          label.frame = currentBasicBlock.frame;
          // - and make sure to NOT assign 'label' into 'currentBasicBlock' or 'lastBasicBlock', so
          // that they still refer to the canonical instance for this bytecode offset.
			// 并且确保不要将label赋值到currentBasicBlock或者lastBasicBlock引用中，这样我们才仍然能够通过字节码偏移量获取到规范实例
          return;
        }
        // End the current basic block (with one new successor).
		  // 将当前的basicBlock结束掉，并且将label设置为currentBasicBlock
        addSuccessorToCurrentBasicBlock(Edge.JUMP, label);
      }
      // Append 'label' at the end of the basic block list.
		// 如果lastBasicBlock不为null的话
      if (lastBasicBlock != null) {
		  // 判断label的offset是否等于lastBasicBlock的offset
        if (label.bytecodeOffset == lastBasicBlock.bytecodeOffset) {
          // Same comment as above.
			// 如果相等的话，同上述的情况一样
          lastBasicBlock.flags |= (label.flags & Label.FLAG_JUMP_TARGET);
          // Here label.frame should be null.
          label.frame = lastBasicBlock.frame;
		  // 将currentBasicBlock设置为lastBasicBlock
          currentBasicBlock = lastBasicBlock;
		  // 然后直接返回
          return;
        }
		// 否则将label连接到lastBasicBlock的next属性上
        lastBasicBlock.nextBasicBlock = label;
      }
	  // 并且将lastBasicBlock引用指向label
      lastBasicBlock = label;
      // Make it the new current basic block.
		// 并且将currentBasicBlock的引用也指向label
      currentBasicBlock = label;
      // Here label.frame should be null.
		// 创建一个StackMapFrame赋值给label的frame属性
      label.frame = new Frame(label);
    } else if (compute == COMPUTE_INSERTED_FRAMES) {
		// 如果currentBasicBlock为null，将label赋值给currentBasicBlock,
		// 这种情况只会出现一次，就是在MethodWriter的构造方法里面调用visitLabel的时候，
		// 如果compute等于COMPUTE_INSERTED_FRAMES的话，那么currentBasicBlock不会改变
      if (currentBasicBlock == null) {
        // This case should happen only once, for the visitLabel call in the constructor. Indeed, if
        // compute is equal to COMPUTE_INSERTED_FRAMES, currentBasicBlock stays unchanged.
        currentBasicBlock = label;
      }
	  // 如果currentBasicBlock不为null的话，
	  else {
        // Update the frame owner so that a correct frame offset is computed in Frame.accept().
		  // 那么更新currentBasicBlock持有的frame的owner属性为传入的label
		  // 因此一个正确的frame offset就会在Frame.accept()方法中计算
        currentBasicBlock.frame.owner = label;
      }
    } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL) {
		// 如果currentBasicBlock不为null的话
      if (currentBasicBlock != null) {
        // End the current basic block (with one new successor).
		  // 将currentBasicBlock的outputStackMax设置为maxRelativeStackSize
        currentBasicBlock.outputStackMax = (short) maxRelativeStackSize;
		// 并且将label添加到currentBasicBlock的successor上
        addSuccessorToCurrentBasicBlock(relativeStackSize, label);
      }
      // Start a new current basic block, and reset the current and maximum relative stack sizes.
		// 将currentBasicBlock引用指向label
      currentBasicBlock = label;
	  // 并且将relativeStackSize和maxRelativeStackSize都重置为0
      relativeStackSize = 0;
      maxRelativeStackSize = 0;
      // Append the new basic block at the end of the basic block list.
		// 改变lastBasicBlock引用，指向label
      if (lastBasicBlock != null) {
        lastBasicBlock.nextBasicBlock = label;
      }
      lastBasicBlock = label;
    } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES && currentBasicBlock == null) {
      // This case should happen only once, for the visitLabel call in the constructor. Indeed, if
      // compute is equal to COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES, currentBasicBlock stays
      // unchanged.
		// 这个情况也只会发生一次，并且compute是COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES时，currentBasicBlock也不会改变
      currentBasicBlock = label;
    }
  }

  @Override
  public void visitLdcInsn(final Object value) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
	  // 根据value的类型向常量池中添加对应类型的常量，并且返回symbol
    Symbol constantSymbol = symbolTable.addConstant(value);
	// 获取常量的索引
    int constantIndex = constantSymbol.index;
    char firstDescriptorChar;
	// 根据symbol的tag判断该常量是什么类型的，是否是long或者double累心功的
    boolean isLongOrDouble =
        constantSymbol.tag == Symbol.CONSTANT_LONG_TAG
            || constantSymbol.tag == Symbol.CONSTANT_DOUBLE_TAG
            || (constantSymbol.tag == Symbol.CONSTANT_DYNAMIC_TAG
                && ((firstDescriptorChar = constantSymbol.value.charAt(0)) == 'J'
                    || firstDescriptorChar == 'D'));
	// 如果是long或者double类型的，那么需要向code中添加1个字节的ldc2_w字节码，2个字节的常量索引
    if (isLongOrDouble) {
      code.put12(Constants.LDC2_W, constantIndex);
    }
	// 如果常量索引大于了256，1个字节无法包含，那么需要使用ldc_w字节码，然后插入两个字节的常量索引
	else if (constantIndex >= 256) {
      code.put12(Constants.LDC_W, constantIndex);
    }
	// 如果小于256，直接使用ldc字节码，然后插入1个字节的常量索引
	else {
      code.put11(Opcodes.LDC, constantIndex);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(Opcodes.LDC, 0, constantSymbol, symbolTable);
      } else {
        int size = relativeStackSize + (isLongOrDouble ? 2 : 1);
        if (size > maxRelativeStackSize) {
          maxRelativeStackSize = size;
        }
        relativeStackSize = size;
      }
    }
  }

  @Override
  public void visitIincInsn(final int var, final int increment) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    if ((var > 255) || (increment > 127) || (increment < -128)) {
      code.putByte(Constants.WIDE).put12(Opcodes.IINC, var).putShort(increment);
    } else {
      code.putByte(Opcodes.IINC).put11(var, increment);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null
        && (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES)) {
      currentBasicBlock.frame.execute(Opcodes.IINC, var, null, null);
    }
    if (compute != COMPUTE_NOTHING) {
      int currentMaxLocals = var + 1;
      if (currentMaxLocals > maxLocals) {
        maxLocals = currentMaxLocals;
      }
    }
  }

  @Override
  public void visitTableSwitchInsn(
      final int min, final int max, final Label dflt, final Label... labels) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    code.putByte(Opcodes.TABLESWITCH).putByteArray(null, 0, (4 - code.length % 4) % 4);
    dflt.put(code, lastBytecodeOffset, true);
    code.putInt(min).putInt(max);
    for (Label label : labels) {
      label.put(code, lastBytecodeOffset, true);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    visitSwitchInsn(dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    code.putByte(Opcodes.LOOKUPSWITCH).putByteArray(null, 0, (4 - code.length % 4) % 4);
    dflt.put(code, lastBytecodeOffset, true);
    code.putInt(labels.length);
    for (int i = 0; i < labels.length; ++i) {
      code.putInt(keys[i]);
      labels[i].put(code, lastBytecodeOffset, true);
    }
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    visitSwitchInsn(dflt, labels);
  }

  private void visitSwitchInsn(final Label dflt, final Label[] labels) {
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES) {
        currentBasicBlock.frame.execute(Opcodes.LOOKUPSWITCH, 0, null, null);
        // Add all the labels as successors of the current basic block.
        addSuccessorToCurrentBasicBlock(Edge.JUMP, dflt);
        dflt.getCanonicalInstance().flags |= Label.FLAG_JUMP_TARGET;
        for (Label label : labels) {
          addSuccessorToCurrentBasicBlock(Edge.JUMP, label);
          label.getCanonicalInstance().flags |= Label.FLAG_JUMP_TARGET;
        }
      } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL) {
        // No need to update maxRelativeStackSize (the stack size delta is always negative).
        --relativeStackSize;
        // Add all the labels as successors of the current basic block.
        addSuccessorToCurrentBasicBlock(relativeStackSize, dflt);
        for (Label label : labels) {
          addSuccessorToCurrentBasicBlock(relativeStackSize, label);
        }
      }
      // End the current basic block.
      endCurrentBasicBlockWithNoSuccessor();
    }
  }

  @Override
  public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
    lastBytecodeOffset = code.length;
    // Add the instruction to the bytecode of the method.
    Symbol descSymbol = symbolTable.addConstantClass(descriptor);
    code.put12(Opcodes.MULTIANEWARRAY, descSymbol.index).putByte(numDimensions);
    // If needed, update the maximum stack size and number of locals, and stack map frames.
    if (currentBasicBlock != null) {
      if (compute == COMPUTE_ALL_FRAMES || compute == COMPUTE_INSERTED_FRAMES) {
        currentBasicBlock.frame.execute(
            Opcodes.MULTIANEWARRAY, numDimensions, descSymbol, symbolTable);
      } else {
        // No need to update maxRelativeStackSize (the stack size delta is always negative).
        relativeStackSize += 1 - numDimensions;
      }
    }
  }

  @Override
  public AnnotationVisitor visitInsnAnnotation(
      final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
    if (visible) {
      return lastCodeRuntimeVisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable,
              (typeRef & 0xFF0000FF) | (lastBytecodeOffset << 8),
              typePath,
              descriptor,
              lastCodeRuntimeVisibleTypeAnnotation);
    } else {
      return lastCodeRuntimeInvisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable,
              (typeRef & 0xFF0000FF) | (lastBytecodeOffset << 8),
              typePath,
              descriptor,
              lastCodeRuntimeInvisibleTypeAnnotation);
    }
  }

  @Override
  public void visitTryCatchBlock(
      final Label start, final Label end, final Label handler, final String type) {
    Handler newHandler =
        new Handler(
            start, end, handler, type != null ? symbolTable.addConstantClass(type).index : 0, type);
    if (firstHandler == null) {
      firstHandler = newHandler;
    } else {
      lastHandler.nextHandler = newHandler;
    }
    lastHandler = newHandler;
  }

  @Override
  public AnnotationVisitor visitTryCatchAnnotation(
      final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
    if (visible) {
      return lastCodeRuntimeVisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable, typeRef, typePath, descriptor, lastCodeRuntimeVisibleTypeAnnotation);
    } else {
      return lastCodeRuntimeInvisibleTypeAnnotation =
          AnnotationWriter.create(
              symbolTable, typeRef, typePath, descriptor, lastCodeRuntimeInvisibleTypeAnnotation);
    }
  }

  @Override
  public void visitLocalVariable(
      final String name,
      final String descriptor,
      final String signature,
      final Label start,
      final Label end,
      final int index) {
    if (signature != null) {
      if (localVariableTypeTable == null) {
        localVariableTypeTable = new ByteVector();
      }
      ++localVariableTypeTableLength;
      localVariableTypeTable
          .putShort(start.bytecodeOffset)
          .putShort(end.bytecodeOffset - start.bytecodeOffset)
          .putShort(symbolTable.addConstantUtf8(name))
          .putShort(symbolTable.addConstantUtf8(signature))
          .putShort(index);
    }
    if (localVariableTable == null) {
      localVariableTable = new ByteVector();
    }
    ++localVariableTableLength;
    localVariableTable
        .putShort(start.bytecodeOffset)
        .putShort(end.bytecodeOffset - start.bytecodeOffset)
        .putShort(symbolTable.addConstantUtf8(name))
        .putShort(symbolTable.addConstantUtf8(descriptor))
        .putShort(index);
    if (compute != COMPUTE_NOTHING) {
      char firstDescChar = descriptor.charAt(0);
      int currentMaxLocals = index + (firstDescChar == 'J' || firstDescChar == 'D' ? 2 : 1);
      if (currentMaxLocals > maxLocals) {
        maxLocals = currentMaxLocals;
      }
    }
  }

  @Override
  public AnnotationVisitor visitLocalVariableAnnotation(
      final int typeRef,
      final TypePath typePath,
      final Label[] start,
      final Label[] end,
      final int[] index,
      final String descriptor,
      final boolean visible) {
    // Create a ByteVector to hold a 'type_annotation' JVMS structure.
    // See https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.7.20.
    ByteVector typeAnnotation = new ByteVector();
    // Write target_type, target_info, and target_path.
    typeAnnotation.putByte(typeRef >>> 24).putShort(start.length);
    for (int i = 0; i < start.length; ++i) {
      typeAnnotation
          .putShort(start[i].bytecodeOffset)
          .putShort(end[i].bytecodeOffset - start[i].bytecodeOffset)
          .putShort(index[i]);
    }
    TypePath.put(typePath, typeAnnotation);
    // Write type_index and reserve space for num_element_value_pairs.
    typeAnnotation.putShort(symbolTable.addConstantUtf8(descriptor)).putShort(0);
    if (visible) {
      return lastCodeRuntimeVisibleTypeAnnotation =
          new AnnotationWriter(
              symbolTable,
              /* useNamedValues = */ true,
              typeAnnotation,
              lastCodeRuntimeVisibleTypeAnnotation);
    } else {
      return lastCodeRuntimeInvisibleTypeAnnotation =
          new AnnotationWriter(
              symbolTable,
              /* useNamedValues = */ true,
              typeAnnotation,
              lastCodeRuntimeInvisibleTypeAnnotation);
    }
  }

  @Override
  public void visitLineNumber(final int line, final Label start) {
    if (lineNumberTable == null) {
      lineNumberTable = new ByteVector();
    }
    ++lineNumberTableLength;
    lineNumberTable.putShort(start.bytecodeOffset);
    lineNumberTable.putShort(line);
  }

  @Override
  public void visitMaxs(final int maxStack, final int maxLocals) {
	  // 如果compute等于COMPUTE_ALL_FRAMES，那么计算maxStack maxLocals 以及 stackMapFrame
    if (compute == COMPUTE_ALL_FRAMES) {
      computeAllFrames();
    } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL) {
      computeMaxStackAndLocal();
    } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL_FROM_FRAMES) {
      this.maxStack = maxRelativeStackSize;
    } else {
      this.maxStack = maxStack;
      this.maxLocals = maxLocals;
    }
  }

  /** Computes all the stack map frames of the method, from scratch. */
  private void computeAllFrames() {
    // Complete the control flow graph with exception handler blocks.
	  // 根据异常处理的block完成控制流程图
    Handler handler = firstHandler;
    while (handler != null) {
      String catchTypeDescriptor =
          handler.catchTypeDescriptor == null ? "java/lang/Throwable" : handler.catchTypeDescriptor;
      int catchType = Frame.getAbstractTypeFromInternalName(symbolTable, catchTypeDescriptor);
      // Mark handlerBlock as an exception handler.
      Label handlerBlock = handler.handlerPc.getCanonicalInstance();
      handlerBlock.flags |= Label.FLAG_JUMP_TARGET;
      // Add handlerBlock as a successor of all the basic blocks in the exception handler range.
      Label handlerRangeBlock = handler.startPc.getCanonicalInstance();
      Label handlerRangeEnd = handler.endPc.getCanonicalInstance();
      while (handlerRangeBlock != handlerRangeEnd) {
        handlerRangeBlock.outgoingEdges =
            new Edge(catchType, handlerBlock, handlerRangeBlock.outgoingEdges);
        handlerRangeBlock = handlerRangeBlock.nextBasicBlock;
      }
      handler = handler.nextHandler;
    }

    // Create and visit the first (implicit) frame.
	  // 创建并且访问first frame
	  // 获取firstBasicBlock的frame对象
    Frame firstFrame = firstBasicBlock.frame;
	// 根据方法的descriptor accessFlags maxLocals设置firstFrame，
	  // 填充firstFrame的inputLocals和inputStack
    firstFrame.setInputFrameFromDescriptor(symbolTable, accessFlags, descriptor, this.maxLocals);
	// 调用firstFrame的accept方法，将methodWriter传入
	  // 根据firstFrame的inputLocals，生成MethodWriter的currentFrame对应的int数组，
	  // 然后依次调用visitAbstractType方法，将inputLocals和inputStack中的抽象类型读到currentFrame中
	  // 最后调用visitFrameEnd方法，根据currentFrame和previousFrame来生成code属性中的StackMapTable属性的内容
    firstFrame.accept(this);

    // Fix point algorithm: add the first basic block to a list of blocks to process (i.e. blocks
    // whose stack map frame has changed) and, while there are blocks to process, remove one from
    // the list and update the stack map frames of its successor blocks in the control flow graph
    // (which might change them, in which case these blocks must be processed too, and are thus
    // added to the list of blocks to process). Also compute the maximum stack size of the method,
    // as a by-product.
    Label listOfBlocksToProcess = firstBasicBlock;
    listOfBlocksToProcess.nextListElement = Label.EMPTY_LIST;
    int maxStackSize = 0;
    while (listOfBlocksToProcess != Label.EMPTY_LIST) {
      // Remove a basic block from the list of blocks to process.
		// 获取链表的头元素
      Label basicBlock = listOfBlocksToProcess;
	  // 然后将listOfBlocksToProcess指针指向链表的下一个元素
      listOfBlocksToProcess = listOfBlocksToProcess.nextListElement;
	  // 将获取到的元素的nextListElement指针指向null，即从链表中断开
      basicBlock.nextListElement = null;
      // By definition, basicBlock is reachable.
		// 将basicBlock的flags中添加FLAG_REACHABLE
      basicBlock.flags |= Label.FLAG_REACHABLE;
      // Update the (absolute) maximum stack size.
		// 计算basicBlock所持有的frame的 maxBlockStackSize = inputStackSize + outputStackMax
      int maxBlockStackSize = basicBlock.frame.getInputStackSize() + basicBlock.outputStackMax;
	  // 如果maxBlockStackSize大于了maxStackSize，将maxStackSize赋值为maxBlockStackSize
      if (maxBlockStackSize > maxStackSize) {
        maxStackSize = maxBlockStackSize;
      }
      // Update the successor blocks of basicBlock in the control flow graph.
		// 获取到basicBlock在控制流程图里面的出边
      Edge outgoingEdge = basicBlock.outgoingEdges;
	  // 如果存在出边的话
      while (outgoingEdge != null) {
		  // 获取到出边指向的下一个basicBlock顶点successorBlock
		  // 如果存在多个basicBlock持有一个frame，那么获取到最开始创建这个frame的label
        Label successorBlock = outgoingEdge.successor.getCanonicalInstance();
		// 调用basicBlock持有的frame的merge方法，对successorBlock的frame进行merge操作
        boolean successorBlockChanged =
            basicBlock.frame.merge(symbolTable, successorBlock.frame, outgoingEdge.info);
        if (successorBlockChanged && successorBlock.nextListElement == null) {
          // If successorBlock has changed it must be processed. Thus, if it is not already in the
          // list of blocks to process, add it to this list.
          successorBlock.nextListElement = listOfBlocksToProcess;
          listOfBlocksToProcess = successorBlock;
        }
        outgoingEdge = outgoingEdge.nextEdge;
      }
    }

    // Loop over all the basic blocks and visit the stack map frames that must be stored in the
    // StackMapTable attribute. Also replace unreachable code with NOP* ATHROW, and remove it from
    // exception handler ranges.
    Label basicBlock = firstBasicBlock;
    while (basicBlock != null) {
      if ((basicBlock.flags & (Label.FLAG_JUMP_TARGET | Label.FLAG_REACHABLE))
          == (Label.FLAG_JUMP_TARGET | Label.FLAG_REACHABLE)) {
        basicBlock.frame.accept(this);
      }
      if ((basicBlock.flags & Label.FLAG_REACHABLE) == 0) {
        // Find the start and end bytecode offsets of this unreachable block.
        Label nextBasicBlock = basicBlock.nextBasicBlock;
        int startOffset = basicBlock.bytecodeOffset;
        int endOffset = (nextBasicBlock == null ? code.length : nextBasicBlock.bytecodeOffset) - 1;
        if (endOffset >= startOffset) {
          // Replace its instructions with NOP ... NOP ATHROW.
          for (int i = startOffset; i < endOffset; ++i) {
            code.data[i] = Opcodes.NOP;
          }
          code.data[endOffset] = (byte) Opcodes.ATHROW;
          // Emit a frame for this unreachable block, with no local and a Throwable on the stack
          // (so that the ATHROW could consume this Throwable if it were reachable).
          int frameIndex = visitFrameStart(startOffset, /* numLocal = */ 0, /* numStack = */ 1);
          currentFrame[frameIndex] =
              Frame.getAbstractTypeFromInternalName(symbolTable, "java/lang/Throwable");
          visitFrameEnd();
          // Remove this unreachable basic block from the exception handler ranges.
          firstHandler = Handler.removeRange(firstHandler, basicBlock, nextBasicBlock);
          // The maximum stack size is now at least one, because of the Throwable declared above.
          maxStackSize = Math.max(maxStackSize, 1);
        }
      }
      basicBlock = basicBlock.nextBasicBlock;
    }

    this.maxStack = maxStackSize;
  }

  /** Computes the maximum stack size of the method. */
  private void computeMaxStackAndLocal() {
    // Complete the control flow graph with exception handler blocks.
    Handler handler = firstHandler;
    while (handler != null) {
      Label handlerBlock = handler.handlerPc;
      Label handlerRangeBlock = handler.startPc;
      Label handlerRangeEnd = handler.endPc;
      // Add handlerBlock as a successor of all the basic blocks in the exception handler range.
      while (handlerRangeBlock != handlerRangeEnd) {
        if ((handlerRangeBlock.flags & Label.FLAG_SUBROUTINE_CALLER) == 0) {
          handlerRangeBlock.outgoingEdges =
              new Edge(Edge.EXCEPTION, handlerBlock, handlerRangeBlock.outgoingEdges);
        } else {
          // If handlerRangeBlock is a JSR block, add handlerBlock after the first two outgoing
          // edges to preserve the hypothesis about JSR block successors order (see
          // {@link #visitJumpInsn}).
          handlerRangeBlock.outgoingEdges.nextEdge.nextEdge =
              new Edge(
                  Edge.EXCEPTION, handlerBlock, handlerRangeBlock.outgoingEdges.nextEdge.nextEdge);
        }
        handlerRangeBlock = handlerRangeBlock.nextBasicBlock;
      }
      handler = handler.nextHandler;
    }

    // Complete the control flow graph with the successor blocks of subroutines, if needed.
    if (hasSubroutines) {
      // First step: find the subroutines. This step determines, for each basic block, to which
      // subroutine(s) it belongs. Start with the main "subroutine":
      short numSubroutines = 1;
      firstBasicBlock.markSubroutine(numSubroutines);
      // Then, mark the subroutines called by the main subroutine, then the subroutines called by
      // those called by the main subroutine, etc.
      for (short currentSubroutine = 1; currentSubroutine <= numSubroutines; ++currentSubroutine) {
        Label basicBlock = firstBasicBlock;
        while (basicBlock != null) {
          if ((basicBlock.flags & Label.FLAG_SUBROUTINE_CALLER) != 0
              && basicBlock.subroutineId == currentSubroutine) {
            Label jsrTarget = basicBlock.outgoingEdges.nextEdge.successor;
            if (jsrTarget.subroutineId == 0) {
              // If this subroutine has not been marked yet, find its basic blocks.
              jsrTarget.markSubroutine(++numSubroutines);
            }
          }
          basicBlock = basicBlock.nextBasicBlock;
        }
      }
      // Second step: find the successors in the control flow graph of each subroutine basic block
      // 'r' ending with a RET instruction. These successors are the virtual successors of the basic
      // blocks ending with JSR instructions (see {@link #visitJumpInsn)} that can reach 'r'.
      Label basicBlock = firstBasicBlock;
      while (basicBlock != null) {
        if ((basicBlock.flags & Label.FLAG_SUBROUTINE_CALLER) != 0) {
          // By construction, jsr targets are stored in the second outgoing edge of basic blocks
          // that ends with a jsr instruction (see {@link #FLAG_SUBROUTINE_CALLER}).
          Label subroutine = basicBlock.outgoingEdges.nextEdge.successor;
          subroutine.addSubroutineRetSuccessors(basicBlock);
        }
        basicBlock = basicBlock.nextBasicBlock;
      }
    }

    // Data flow algorithm: put the first basic block in a list of blocks to process (i.e. blocks
    // whose input stack size has changed) and, while there are blocks to process, remove one
    // from the list, update the input stack size of its successor blocks in the control flow
    // graph, and add these blocks to the list of blocks to process (if not already done).
    Label listOfBlocksToProcess = firstBasicBlock;
    listOfBlocksToProcess.nextListElement = Label.EMPTY_LIST;
    int maxStackSize = maxStack;
    while (listOfBlocksToProcess != Label.EMPTY_LIST) {
      // Remove a basic block from the list of blocks to process. Note that we don't reset
      // basicBlock.nextListElement to null on purpose, to make sure we don't reprocess already
      // processed basic blocks.
      Label basicBlock = listOfBlocksToProcess;
      listOfBlocksToProcess = listOfBlocksToProcess.nextListElement;
      // Compute the (absolute) input stack size and maximum stack size of this block.
      int inputStackTop = basicBlock.inputStackSize;
      int maxBlockStackSize = inputStackTop + basicBlock.outputStackMax;
      // Update the absolute maximum stack size of the method.
      if (maxBlockStackSize > maxStackSize) {
        maxStackSize = maxBlockStackSize;
      }
      // Update the input stack size of the successor blocks of basicBlock in the control flow
      // graph, and add these blocks to the list of blocks to process, if not already done.
      Edge outgoingEdge = basicBlock.outgoingEdges;
      if ((basicBlock.flags & Label.FLAG_SUBROUTINE_CALLER) != 0) {
        // Ignore the first outgoing edge of the basic blocks ending with a jsr: these are virtual
        // edges which lead to the instruction just after the jsr, and do not correspond to a
        // possible execution path (see {@link #visitJumpInsn} and
        // {@link Label#FLAG_SUBROUTINE_CALLER}).
        outgoingEdge = outgoingEdge.nextEdge;
      }
      while (outgoingEdge != null) {
        Label successorBlock = outgoingEdge.successor;
        if (successorBlock.nextListElement == null) {
          successorBlock.inputStackSize =
              (short) (outgoingEdge.info == Edge.EXCEPTION ? 1 : inputStackTop + outgoingEdge.info);
          successorBlock.nextListElement = listOfBlocksToProcess;
          listOfBlocksToProcess = successorBlock;
        }
        outgoingEdge = outgoingEdge.nextEdge;
      }
    }
    this.maxStack = maxStackSize;
  }

  @Override
  public void visitEnd() {
    // Nothing to do.
  }

  // -----------------------------------------------------------------------------------------------
  // Utility methods: control flow analysis algorithm
  // -----------------------------------------------------------------------------------------------

  /**
   * Adds a successor to {@link #currentBasicBlock} in the control flow graph.
   *
   * @param info information about the control flow edge to be added.
   * @param successor the successor block to be added to the current basic block.
   */
  private void addSuccessorToCurrentBasicBlock(final int info, final Label successor) {
	  // 创建一个Edge赋值给currentBasicBlock，向currentBasicBlock添加一个successor
	  // currentBasicBlock作为图的一个顶点，创建其指向successor顶点的边，
	  // 并且将currentBasicBlock原本的那些边添加到当前edge的next，构成currentBasicBlock指出边的链表
    currentBasicBlock.outgoingEdges = new Edge(info, successor, currentBasicBlock.outgoingEdges);
  }

  /**
   * Ends the current basic block. This method must be used in the case where the current basic
   * block does not have any successor.
   *
   * <p>WARNING: this method must be called after the currently visited instruction has been put in
   * {@link #code} (if frames are computed, this method inserts a new Label to start a new basic
   * block after the current instruction).
   */
  private void endCurrentBasicBlockWithNoSuccessor() {
    if (compute == COMPUTE_ALL_FRAMES) {
      Label nextBasicBlock = new Label();
      nextBasicBlock.frame = new Frame(nextBasicBlock);
      nextBasicBlock.resolve(code.data, code.length);
      lastBasicBlock.nextBasicBlock = nextBasicBlock;
      lastBasicBlock = nextBasicBlock;
      currentBasicBlock = null;
    } else if (compute == COMPUTE_MAX_STACK_AND_LOCAL) {
      currentBasicBlock.outputStackMax = (short) maxRelativeStackSize;
      currentBasicBlock = null;
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Utility methods: stack map frames
  // -----------------------------------------------------------------------------------------------

  /**
   * Starts the visit of a new stack map frame, stored in {@link #currentFrame}.
   *
   * @param offset the bytecode offset of the instruction to which the frame corresponds.
   * @param numLocal the number of local variables in the frame.
   * @param numStack the number of stack elements in the frame.
   * @return the index of the next element to be written in this frame.
   */
  int visitFrameStart(final int offset, final int numLocal, final int numStack) {
	  // 先定义frame数组的length为3 + numLocal + numStack
    int frameLength = 3 + numLocal + numStack;
	// 如果currentFrame为null 或者 currentFrame的length 比 计算出来的frameLength小的话
	  // 根据frameLength创建一个int数组赋值给currentFrame
    if (currentFrame == null || currentFrame.length < frameLength) {
      currentFrame = new int[frameLength];
    }
	// 设置currentFrame的前三个元素
    currentFrame[0] = offset;
    currentFrame[1] = numLocal;
    currentFrame[2] = numStack;
    return 3;
  }

  /**
   * Sets an abstract type in {@link #currentFrame}.
   *
   * @param frameIndex the index of the element to be set in {@link #currentFrame}.
   * @param abstractType an abstract type.
   */
  void visitAbstractType(final int frameIndex, final int abstractType) {
    currentFrame[frameIndex] = abstractType;
  }

  /**
   * Ends the visit of {@link #currentFrame} by writing it in the StackMapTable entries and by
   * updating the StackMapTable number_of_entries (except if the current frame is the first one,
   * which is implicit in StackMapTable). Then resets {@link #currentFrame} to {@literal null}.
   */
  void visitFrameEnd() {
	  // 如果previousFrame不为null的话，才执行下面的逻辑，因为如果为null的话，说明是第一个frame，不会体现在StackMapTable属性中
    if (previousFrame != null) {
		// 并且stackMapTableEntries也不为null的话
      if (stackMapTableEntries == null) {
		  // 初始化stackMapTableEntries为一个ByteVector，即字节数组
        stackMapTableEntries = new ByteVector();
      }
	  // 然后调用putFrame方法，将currentFrame的信息存入到StackMapFrameEntries中
      putFrame();
	  // 将stackMapTable中的entries数量+1
      ++stackMapTableNumberOfEntries;
    }
	// 然后将currentFrame赋值给previousFrame
    previousFrame = currentFrame;
	// 将currentFrame置为null
    currentFrame = null;
  }

  /** Compresses and writes {@link #currentFrame} in a new StackMapTable entry. */
  private void putFrame() {
	  // 获取locals和stack的数量
    final int numLocal = currentFrame[1];
    final int numStack = currentFrame[2];
	// 如果class文件的版本小于1.6
    if (symbolTable.getMajorVersion() < Opcodes.V1_6) {
      // Generate a StackMap attribute entry, which are always uncompressed.
		// 总是生成一个未压缩的StackMapEntry，相当于之后的full_frame类型
		// 1.首先设置2个字节的字节码偏移量
		// 2.然后设置2个字节的numLocal
		// 3.设置locals到entry中
		// 4.设置2个字节的numStack
		// 5.设置stack到entry中
      stackMapTableEntries.putShort(currentFrame[0]).putShort(numLocal);
      putAbstractTypes(3, 3 + numLocal);
      stackMapTableEntries.putShort(numStack);
      putAbstractTypes(3 + numLocal, 3 + numLocal + numStack);
      return;
    }
	// 大于等于1.6版本的class文件，会使用压缩后的frame，即根据frame_type来区分
	  // 首先计算offsetDelta，如果是第二个stackMapFrame(第一个stackMapFrame会被忽略)，offsetDelta就等于字节码的偏移量
	  // 后续的offsetDelta都等于自身的字节码偏移量 - 前一个frame的字节码偏移量 - 1
    final int offsetDelta =
        stackMapTableNumberOfEntries == 0
            ? currentFrame[0]
            : currentFrame[0] - previousFrame[0] - 1;
	// 获取前一个frame的inputLocals的数量
    final int previousNumlocal = previousFrame[1];
	// 用当前frame的inputLocals数量 - previousNumLocal，得到inputLocals的差值
    final int numLocalDelta = numLocal - previousNumlocal;
	// 默认的frame_type是FULL_FRAME，即255
    int type = Frame.FULL_FRAME;
	// 如果当前frame的inputStack数量为0
    if (numStack == 0) {
		// 根据inputLocals数量的差值进行判断
      switch (numLocalDelta) {
        case -3:
        case -2:
        case -1:
			// 如果差值等于-3 -2 -1，那么frame_type是CHOP_FRAME类型，取值区间为[248,250]
          type = Frame.CHOP_FRAME;
          break;
		  // 如果差值等于0，根据offsetDelta来判断，如果小于64的话，是SAME_FRAME[0-63]，否则是SAME_FRAME_EXTENDED = 251
        case 0:
          type = offsetDelta < 64 ? Frame.SAME_FRAME : Frame.SAME_FRAME_EXTENDED;
          break;
		  // 如果差值等于1 2 3
        case 1:
        case 2:
        case 3:
			// frame_type是APPEND_FRAME，取值区间是[252,254]
          type = Frame.APPEND_FRAME;
          break;
		  // 其他情况都是FULL_FRAME
        default:
          // Keep the FULL_FRAME type.
          break;
      }
    }
	// 当inputLocals的数量差为0，并且当前frame存在一个inputStack
	else if (numLocalDelta == 0 && numStack == 1) {
		// 根据offsetDelta来判断frame_type是SAME_LOCALS_1_STACK_ITEM_FRAME(取值范围是[64,127))
		// 还是 SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED(取值范围是 = 247)
      type =
          offsetDelta < 63
              ? Frame.SAME_LOCALS_1_STACK_ITEM_FRAME
              : Frame.SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED;
    }
	// 如果type不是FULL_FRAME
    if (type != Frame.FULL_FRAME) {
      // Verify if locals are the same as in the previous frame.
      int frameIndex = 3;
	  // 验证当前frame和previousFrame的inputLocals是否一致
		// i要小于当前的frame的local数量并且也要小于previousFrame的local数量
      for (int i = 0; i < previousNumlocal && i < numLocal; i++) {
		  // 如果不一致的话，将type转换为FULL_FRAME
        if (currentFrame[frameIndex] != previousFrame[frameIndex]) {
          type = Frame.FULL_FRAME;
          break;
        }
        frameIndex++;
      }
    }
	// 根据上面得出的frame_type，向stackMapTable的字节数组中添加字节
    switch (type) {
		// 如果是SAME_FRAME，将offsetDelta作为frame_type存入，取值只能为[0,63]
      case Frame.SAME_FRAME:
        stackMapTableEntries.putByte(offsetDelta);
        break;
		// SAME_LOCALS_1_STACK_ITEM_FRAME，将offsetDelta + 64作为frame_type存入，取值范围为[64,127)
      case Frame.SAME_LOCALS_1_STACK_ITEM_FRAME:
        stackMapTableEntries.putByte(Frame.SAME_LOCALS_1_STACK_ITEM_FRAME + offsetDelta);
		// 然后存入第一个inputStack的抽象类型对应的verification_type_info
        putAbstractTypes(3 + numLocal, 4 + numLocal);
        break;
		// SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED，frame_type默认127，然后再存入两个字节的offsetDelta，
		// 然后存入第一个inputStack的抽象类型对应的verification_type_info
      case Frame.SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED:
        stackMapTableEntries
            .putByte(Frame.SAME_LOCALS_1_STACK_ITEM_FRAME_EXTENDED)
            .putShort(offsetDelta);
        putAbstractTypes(3 + numLocal, 4 + numLocal);
        break;
		// SAME_FRAME_EXTENDED, frame_type默认为251，然后存入两个字节offsetDelta
      case Frame.SAME_FRAME_EXTENDED:
        stackMapTableEntries.putByte(Frame.SAME_FRAME_EXTENDED).putShort(offsetDelta);
        break;
		// CHOP_FRAME，frame_type取值范围是[248,250]，根据inputLocals的数量差来决定，先存入一个字节的frame_type，
		// 然后存入两个字节的offsetDelta
      case Frame.CHOP_FRAME:
        stackMapTableEntries
            .putByte(Frame.SAME_FRAME_EXTENDED + numLocalDelta)
            .putShort(offsetDelta);
        break;
		// APPEND_FRAME，frame_type取值范围[252,254]，也是根据inputLocals的数量差来决定，先存入一个字节的frame_type,
		// 然后存入两个字节的offsetDelta，然后在存入新增加的inputLocals对应的抽象类型对应的verification_type_info
      case Frame.APPEND_FRAME:
        stackMapTableEntries
            .putByte(Frame.SAME_FRAME_EXTENDED + numLocalDelta)
            .putShort(offsetDelta);
        putAbstractTypes(3 + previousNumlocal, 3 + numLocal);
        break;
		// 其他情况都是FULL_FRAME，存入一个字节的frame_type，默认为255，然后存入两个字节的offsetDelta，
		// 然后存入当前frame全量的inputLocals和inputStack
      case Frame.FULL_FRAME:
      default:
        stackMapTableEntries.putByte(Frame.FULL_FRAME).putShort(offsetDelta).putShort(numLocal);
        putAbstractTypes(3, 3 + numLocal);
        stackMapTableEntries.putShort(numStack);
        putAbstractTypes(3 + numLocal, 3 + numLocal + numStack);
        break;
    }
  }

  /**
   * Puts some abstract types of {@link #currentFrame} in {@link #stackMapTableEntries} , using the
   * JVMS verification_type_info format used in StackMapTable attributes.
   *
   * @param start index of the first type in {@link #currentFrame} to write.
   * @param end index of last type in {@link #currentFrame} to write (exclusive).
   */
  private void putAbstractTypes(final int start, final int end) {
	  // 遍历currentFrame从start到end下标的抽象类型，依次放入到stackMapTableEntries这个字节数组中
    for (int i = start; i < end; ++i) {
      Frame.putAbstractType(symbolTable, currentFrame[i], stackMapTableEntries);
    }
  }

  /**
   * Puts the given public API frame element type in {@link #stackMapTableEntries} , using the JVMS
   * verification_type_info format used in StackMapTable attributes.
   *
   * @param type a frame element type described using the same format as in {@link
   *     MethodVisitor#visitFrame}, i.e. either {@link Opcodes#TOP}, {@link Opcodes#INTEGER}, {@link
   *     Opcodes#FLOAT}, {@link Opcodes#LONG}, {@link Opcodes#DOUBLE}, {@link Opcodes#NULL}, or
   *     {@link Opcodes#UNINITIALIZED_THIS}, or the internal name of a class, or a Label designating
   *     a NEW instruction (for uninitialized types).
   */
  private void putFrameType(final Object type) {
    if (type instanceof Integer) {
      stackMapTableEntries.putByte(((Integer) type).intValue());
    } else if (type instanceof String) {
      stackMapTableEntries
          .putByte(Frame.ITEM_OBJECT)
          .putShort(symbolTable.addConstantClass((String) type).index);
    } else {
      stackMapTableEntries
          .putByte(Frame.ITEM_UNINITIALIZED)
          .putShort(((Label) type).bytecodeOffset);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Utility methods
  // -----------------------------------------------------------------------------------------------

  /**
   * Returns whether the attributes of this method can be copied from the attributes of the given
   * method (assuming there is no method visitor between the given ClassReader and this
   * MethodWriter). This method should only be called just after this MethodWriter has been created,
   * and before any content is visited. It returns true if the attributes corresponding to the
   * constructor arguments (at most a Signature, an Exception, a Deprecated and a Synthetic
   * attribute) are the same as the corresponding attributes in the given method.
   *
   * @param source the source ClassReader from which the attributes of this method might be copied.
   * @param hasSyntheticAttribute whether the method_info JVMS structure from which the attributes
   *     of this method might be copied contains a Synthetic attribute.
   * @param hasDeprecatedAttribute whether the method_info JVMS structure from which the attributes
   *     of this method might be copied contains a Deprecated attribute.
   * @param descriptorIndex the descriptor_index field of the method_info JVMS structure from which
   *     the attributes of this method might be copied.
   * @param signatureIndex the constant pool index contained in the Signature attribute of the
   *     method_info JVMS structure from which the attributes of this method might be copied, or 0.
   * @param exceptionsOffset the offset in 'source.b' of the Exceptions attribute of the method_info
   *     JVMS structure from which the attributes of this method might be copied, or 0.
   * @return whether the attributes of this method can be copied from the attributes of the
   *     method_info JVMS structure in 'source.b', between 'methodInfoOffset' and 'methodInfoOffset'
   *     + 'methodInfoLength'.
   */
  boolean canCopyMethodAttributes(
      final ClassReader source,
      final boolean hasSyntheticAttribute,
      final boolean hasDeprecatedAttribute,
      final int descriptorIndex,
      final int signatureIndex,
      final int exceptionsOffset) {
    // If the method descriptor has changed, with more locals than the max_locals field of the
    // original Code attribute, if any, then the original method attributes can't be copied. A
    // conservative check on the descriptor changes alone ensures this (being more precise is not
    // worth the additional complexity, because these cases should be rare -- if a transform changes
    // a method descriptor, most of the time it needs to change the method's code too).
    if (source != symbolTable.getSource()
        || descriptorIndex != this.descriptorIndex
        || signatureIndex != this.signatureIndex
        || hasDeprecatedAttribute != ((accessFlags & Opcodes.ACC_DEPRECATED) != 0)) {
      return false;
    }
    boolean needSyntheticAttribute =
        symbolTable.getMajorVersion() < Opcodes.V1_5 && (accessFlags & Opcodes.ACC_SYNTHETIC) != 0;
    if (hasSyntheticAttribute != needSyntheticAttribute) {
      return false;
    }
    if (exceptionsOffset == 0) {
      if (numberOfExceptions != 0) {
        return false;
      }
    } else if (source.readUnsignedShort(exceptionsOffset) == numberOfExceptions) {
      int currentExceptionOffset = exceptionsOffset + 2;
      for (int i = 0; i < numberOfExceptions; ++i) {
        if (source.readUnsignedShort(currentExceptionOffset) != exceptionIndexTable[i]) {
          return false;
        }
        currentExceptionOffset += 2;
      }
    }
    return true;
  }

  /**
   * Sets the source from which the attributes of this method will be copied.
   *
   * @param methodInfoOffset the offset in 'symbolTable.getSource()' of the method_info JVMS
   *     structure from which the attributes of this method will be copied.
   * @param methodInfoLength the length in 'symbolTable.getSource()' of the method_info JVMS
   *     structure from which the attributes of this method will be copied.
   */
  void setMethodAttributesSource(final int methodInfoOffset, final int methodInfoLength) {
    // Don't copy the attributes yet, instead store their location in the source class reader so
    // they can be copied later, in {@link #putMethodInfo}. Note that we skip the 6 header bytes
    // of the method_info JVMS structure.
    this.sourceOffset = methodInfoOffset + 6;
    this.sourceLength = methodInfoLength - 6;
  }

  /**
   * Returns the size of the method_info JVMS structure generated by this MethodWriter. Also add the
   * names of the attributes of this method in the constant pool.
   *
   * @return the size in bytes of the method_info JVMS structure.
   */
  int computeMethodInfoSize() {
    // If this method_info must be copied from an existing one, the size computation is trivial.
    if (sourceOffset != 0) {
      // sourceLength excludes the first 6 bytes for access_flags, name_index and descriptor_index.
      return 6 + sourceLength;
    }
    // 2 bytes each for access_flags, name_index, descriptor_index and attributes_count.
    int size = 8;
    // For ease of reference, we use here the same attribute order as in Section 4.7 of the JVMS.
    if (code.length > 0) {
      if (code.length > 65535) {
        throw new MethodTooLargeException(
            symbolTable.getClassName(), name, descriptor, code.length);
      }
      symbolTable.addConstantUtf8(Constants.CODE);
      // The Code attribute has 6 header bytes, plus 2, 2, 4 and 2 bytes respectively for max_stack,
      // max_locals, code_length and attributes_count, plus the bytecode and the exception table.
      size += 16 + code.length + Handler.getExceptionTableSize(firstHandler);
      if (stackMapTableEntries != null) {
        boolean useStackMapTable = symbolTable.getMajorVersion() >= Opcodes.V1_6;
        symbolTable.addConstantUtf8(useStackMapTable ? Constants.STACK_MAP_TABLE : "StackMap");
        // 6 header bytes and 2 bytes for number_of_entries.
        size += 8 + stackMapTableEntries.length;
      }
      if (lineNumberTable != null) {
        symbolTable.addConstantUtf8(Constants.LINE_NUMBER_TABLE);
        // 6 header bytes and 2 bytes for line_number_table_length.
        size += 8 + lineNumberTable.length;
      }
      if (localVariableTable != null) {
        symbolTable.addConstantUtf8(Constants.LOCAL_VARIABLE_TABLE);
        // 6 header bytes and 2 bytes for local_variable_table_length.
        size += 8 + localVariableTable.length;
      }
      if (localVariableTypeTable != null) {
        symbolTable.addConstantUtf8(Constants.LOCAL_VARIABLE_TYPE_TABLE);
        // 6 header bytes and 2 bytes for local_variable_type_table_length.
        size += 8 + localVariableTypeTable.length;
      }
      if (lastCodeRuntimeVisibleTypeAnnotation != null) {
        size +=
            lastCodeRuntimeVisibleTypeAnnotation.computeAnnotationsSize(
                Constants.RUNTIME_VISIBLE_TYPE_ANNOTATIONS);
      }
      if (lastCodeRuntimeInvisibleTypeAnnotation != null) {
        size +=
            lastCodeRuntimeInvisibleTypeAnnotation.computeAnnotationsSize(
                Constants.RUNTIME_INVISIBLE_TYPE_ANNOTATIONS);
      }
      if (firstCodeAttribute != null) {
        size +=
            firstCodeAttribute.computeAttributesSize(
                symbolTable, code.data, code.length, maxStack, maxLocals);
      }
    }
    if (numberOfExceptions > 0) {
      symbolTable.addConstantUtf8(Constants.EXCEPTIONS);
      size += 8 + 2 * numberOfExceptions;
    }
    size += Attribute.computeAttributesSize(symbolTable, accessFlags, signatureIndex);
    size +=
        AnnotationWriter.computeAnnotationsSize(
            lastRuntimeVisibleAnnotation,
            lastRuntimeInvisibleAnnotation,
            lastRuntimeVisibleTypeAnnotation,
            lastRuntimeInvisibleTypeAnnotation);
    if (lastRuntimeVisibleParameterAnnotations != null) {
      size +=
          AnnotationWriter.computeParameterAnnotationsSize(
              Constants.RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS,
              lastRuntimeVisibleParameterAnnotations,
              visibleAnnotableParameterCount == 0
                  ? lastRuntimeVisibleParameterAnnotations.length
                  : visibleAnnotableParameterCount);
    }
    if (lastRuntimeInvisibleParameterAnnotations != null) {
      size +=
          AnnotationWriter.computeParameterAnnotationsSize(
              Constants.RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS,
              lastRuntimeInvisibleParameterAnnotations,
              invisibleAnnotableParameterCount == 0
                  ? lastRuntimeInvisibleParameterAnnotations.length
                  : invisibleAnnotableParameterCount);
    }
    if (defaultValue != null) {
      symbolTable.addConstantUtf8(Constants.ANNOTATION_DEFAULT);
      size += 6 + defaultValue.length;
    }
    if (parameters != null) {
      symbolTable.addConstantUtf8(Constants.METHOD_PARAMETERS);
      // 6 header bytes and 1 byte for parameters_count.
      size += 7 + parameters.length;
    }
    if (firstAttribute != null) {
      size += firstAttribute.computeAttributesSize(symbolTable);
    }
    return size;
  }

  /**
   * Puts the content of the method_info JVMS structure generated by this MethodWriter into the
   * given ByteVector.
   *
   * @param output where the method_info structure must be put.
   */
  void putMethodInfo(final ByteVector output) {
    boolean useSyntheticAttribute = symbolTable.getMajorVersion() < Opcodes.V1_5;
    int mask = useSyntheticAttribute ? Opcodes.ACC_SYNTHETIC : 0;
    output.putShort(accessFlags & ~mask).putShort(nameIndex).putShort(descriptorIndex);
    // If this method_info must be copied from an existing one, copy it now and return early.
    if (sourceOffset != 0) {
      output.putByteArray(symbolTable.getSource().classFileBuffer, sourceOffset, sourceLength);
      return;
    }
    // For ease of reference, we use here the same attribute order as in Section 4.7 of the JVMS.
    int attributeCount = 0;
    if (code.length > 0) {
      ++attributeCount;
    }
    if (numberOfExceptions > 0) {
      ++attributeCount;
    }
    if ((accessFlags & Opcodes.ACC_SYNTHETIC) != 0 && useSyntheticAttribute) {
      ++attributeCount;
    }
    if (signatureIndex != 0) {
      ++attributeCount;
    }
    if ((accessFlags & Opcodes.ACC_DEPRECATED) != 0) {
      ++attributeCount;
    }
    if (lastRuntimeVisibleAnnotation != null) {
      ++attributeCount;
    }
    if (lastRuntimeInvisibleAnnotation != null) {
      ++attributeCount;
    }
    if (lastRuntimeVisibleParameterAnnotations != null) {
      ++attributeCount;
    }
    if (lastRuntimeInvisibleParameterAnnotations != null) {
      ++attributeCount;
    }
    if (lastRuntimeVisibleTypeAnnotation != null) {
      ++attributeCount;
    }
    if (lastRuntimeInvisibleTypeAnnotation != null) {
      ++attributeCount;
    }
    if (defaultValue != null) {
      ++attributeCount;
    }
    if (parameters != null) {
      ++attributeCount;
    }
    if (firstAttribute != null) {
      attributeCount += firstAttribute.getAttributeCount();
    }
    // For ease of reference, we use here the same attribute order as in Section 4.7 of the JVMS.
    output.putShort(attributeCount);
    if (code.length > 0) {
      // 2, 2, 4 and 2 bytes respectively for max_stack, max_locals, code_length and
      // attributes_count, plus the bytecode and the exception table.
      int size = 10 + code.length + Handler.getExceptionTableSize(firstHandler);
      int codeAttributeCount = 0;
      if (stackMapTableEntries != null) {
        // 6 header bytes and 2 bytes for number_of_entries.
        size += 8 + stackMapTableEntries.length;
        ++codeAttributeCount;
      }
      if (lineNumberTable != null) {
        // 6 header bytes and 2 bytes for line_number_table_length.
        size += 8 + lineNumberTable.length;
        ++codeAttributeCount;
      }
      if (localVariableTable != null) {
        // 6 header bytes and 2 bytes for local_variable_table_length.
        size += 8 + localVariableTable.length;
        ++codeAttributeCount;
      }
      if (localVariableTypeTable != null) {
        // 6 header bytes and 2 bytes for local_variable_type_table_length.
        size += 8 + localVariableTypeTable.length;
        ++codeAttributeCount;
      }
      if (lastCodeRuntimeVisibleTypeAnnotation != null) {
        size +=
            lastCodeRuntimeVisibleTypeAnnotation.computeAnnotationsSize(
                Constants.RUNTIME_VISIBLE_TYPE_ANNOTATIONS);
        ++codeAttributeCount;
      }
      if (lastCodeRuntimeInvisibleTypeAnnotation != null) {
        size +=
            lastCodeRuntimeInvisibleTypeAnnotation.computeAnnotationsSize(
                Constants.RUNTIME_INVISIBLE_TYPE_ANNOTATIONS);
        ++codeAttributeCount;
      }
      if (firstCodeAttribute != null) {
        size +=
            firstCodeAttribute.computeAttributesSize(
                symbolTable, code.data, code.length, maxStack, maxLocals);
        codeAttributeCount += firstCodeAttribute.getAttributeCount();
      }
      output
          .putShort(symbolTable.addConstantUtf8(Constants.CODE))
          .putInt(size)
          .putShort(maxStack)
          .putShort(maxLocals)
          .putInt(code.length)
          .putByteArray(code.data, 0, code.length);
      Handler.putExceptionTable(firstHandler, output);
      output.putShort(codeAttributeCount);
      if (stackMapTableEntries != null) {
        boolean useStackMapTable = symbolTable.getMajorVersion() >= Opcodes.V1_6;
        output
            .putShort(
                symbolTable.addConstantUtf8(
                    useStackMapTable ? Constants.STACK_MAP_TABLE : "StackMap"))
            .putInt(2 + stackMapTableEntries.length)
            .putShort(stackMapTableNumberOfEntries)
            .putByteArray(stackMapTableEntries.data, 0, stackMapTableEntries.length);
      }
      if (lineNumberTable != null) {
        output
            .putShort(symbolTable.addConstantUtf8(Constants.LINE_NUMBER_TABLE))
            .putInt(2 + lineNumberTable.length)
            .putShort(lineNumberTableLength)
            .putByteArray(lineNumberTable.data, 0, lineNumberTable.length);
      }
      if (localVariableTable != null) {
        output
            .putShort(symbolTable.addConstantUtf8(Constants.LOCAL_VARIABLE_TABLE))
            .putInt(2 + localVariableTable.length)
            .putShort(localVariableTableLength)
            .putByteArray(localVariableTable.data, 0, localVariableTable.length);
      }
      if (localVariableTypeTable != null) {
        output
            .putShort(symbolTable.addConstantUtf8(Constants.LOCAL_VARIABLE_TYPE_TABLE))
            .putInt(2 + localVariableTypeTable.length)
            .putShort(localVariableTypeTableLength)
            .putByteArray(localVariableTypeTable.data, 0, localVariableTypeTable.length);
      }
      if (lastCodeRuntimeVisibleTypeAnnotation != null) {
        lastCodeRuntimeVisibleTypeAnnotation.putAnnotations(
            symbolTable.addConstantUtf8(Constants.RUNTIME_VISIBLE_TYPE_ANNOTATIONS), output);
      }
      if (lastCodeRuntimeInvisibleTypeAnnotation != null) {
        lastCodeRuntimeInvisibleTypeAnnotation.putAnnotations(
            symbolTable.addConstantUtf8(Constants.RUNTIME_INVISIBLE_TYPE_ANNOTATIONS), output);
      }
      if (firstCodeAttribute != null) {
        firstCodeAttribute.putAttributes(
            symbolTable, code.data, code.length, maxStack, maxLocals, output);
      }
    }
    if (numberOfExceptions > 0) {
      output
          .putShort(symbolTable.addConstantUtf8(Constants.EXCEPTIONS))
          .putInt(2 + 2 * numberOfExceptions)
          .putShort(numberOfExceptions);
      for (int exceptionIndex : exceptionIndexTable) {
        output.putShort(exceptionIndex);
      }
    }
    Attribute.putAttributes(symbolTable, accessFlags, signatureIndex, output);
    AnnotationWriter.putAnnotations(
        symbolTable,
        lastRuntimeVisibleAnnotation,
        lastRuntimeInvisibleAnnotation,
        lastRuntimeVisibleTypeAnnotation,
        lastRuntimeInvisibleTypeAnnotation,
        output);
    if (lastRuntimeVisibleParameterAnnotations != null) {
      AnnotationWriter.putParameterAnnotations(
          symbolTable.addConstantUtf8(Constants.RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS),
          lastRuntimeVisibleParameterAnnotations,
          visibleAnnotableParameterCount == 0
              ? lastRuntimeVisibleParameterAnnotations.length
              : visibleAnnotableParameterCount,
          output);
    }
    if (lastRuntimeInvisibleParameterAnnotations != null) {
      AnnotationWriter.putParameterAnnotations(
          symbolTable.addConstantUtf8(Constants.RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS),
          lastRuntimeInvisibleParameterAnnotations,
          invisibleAnnotableParameterCount == 0
              ? lastRuntimeInvisibleParameterAnnotations.length
              : invisibleAnnotableParameterCount,
          output);
    }
    if (defaultValue != null) {
      output
          .putShort(symbolTable.addConstantUtf8(Constants.ANNOTATION_DEFAULT))
          .putInt(defaultValue.length)
          .putByteArray(defaultValue.data, 0, defaultValue.length);
    }
    if (parameters != null) {
      output
          .putShort(symbolTable.addConstantUtf8(Constants.METHOD_PARAMETERS))
          .putInt(1 + parameters.length)
          .putByte(parametersCount)
          .putByteArray(parameters.data, 0, parameters.length);
    }
    if (firstAttribute != null) {
      firstAttribute.putAttributes(symbolTable, output);
    }
  }

  /**
   * Collects the attributes of this method into the given set of attribute prototypes.
   *
   * @param attributePrototypes a set of attribute prototypes.
   */
  final void collectAttributePrototypes(final Attribute.Set attributePrototypes) {
    attributePrototypes.addAttributes(firstAttribute);
    attributePrototypes.addAttributes(firstCodeAttribute);
  }
}
